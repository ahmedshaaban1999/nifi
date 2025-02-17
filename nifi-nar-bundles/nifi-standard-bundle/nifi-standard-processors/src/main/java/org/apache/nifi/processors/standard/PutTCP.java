/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.standard;

import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.InputRequirement.Requirement;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.event.transport.configuration.TransportProtocol;
import org.apache.nifi.event.transport.netty.DelimitedInputStream;
import org.apache.nifi.event.transport.netty.NettyEventSenderFactory;
import org.apache.nifi.event.transport.netty.StreamingNettyEventSenderFactory;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessSessionFactory;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.util.put.AbstractPutEventProcessor;
import org.apache.nifi.util.StopWatch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@CapabilityDescription("The PutTCP processor receives a FlowFile and transmits the FlowFile content over a TCP connection to the configured TCP server. "
        + "By default, the FlowFiles are transmitted over the same TCP connection (or pool of TCP connections if multiple input threads are configured). "
        + "To assist the TCP server with determining message boundaries, an optional \"Outgoing Message Delimiter\" string can be configured which is appended "
        + "to the end of each FlowFiles content when it is transmitted over the TCP connection. An optional \"Connection Per FlowFile\" parameter can be "
        + "specified to change the behaviour so that each FlowFiles content is transmitted over a single TCP connection which is opened when the FlowFile "
        + "is received and closed after the FlowFile has been sent. This option should only be used for low message volume scenarios, otherwise the platform " + "may run out of TCP sockets.")
@InputRequirement(Requirement.INPUT_REQUIRED)
@SeeAlso({ListenTCP.class, PutUDP.class})
@Tags({ "remote", "egress", "put", "tcp" })
@SupportsBatching
public class PutTCP extends AbstractPutEventProcessor {

    @Override
    protected String createTransitUri(final ProcessContext context) {
        final String protocol = TCP_VALUE.getValue();
        final String host = context.getProperty(HOSTNAME).evaluateAttributeExpressions().getValue();
        final String port = context.getProperty(PORT).evaluateAttributeExpressions().getValue();

        return new StringBuilder().append(protocol).append("://").append(host).append(":").append(port).toString();
    }

    @Override
    protected List<PropertyDescriptor> getAdditionalProperties() {
        return Arrays.asList(CONNECTION_PER_FLOWFILE,
                OUTGOING_MESSAGE_DELIMITER,
                TIMEOUT,
                SSL_CONTEXT_SERVICE,
                CHARSET);
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSessionFactory sessionFactory) throws ProcessException {
        final ProcessSession session = sessionFactory.createSession();
        final FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        try {
            StopWatch stopWatch = new StopWatch(true);
            session.read(flowFile, new InputStreamCallback() {
                @Override
                public void process(final InputStream in) throws IOException {
                    InputStream event = in;

                    String delimiter = getOutgoingMessageDelimiter(context, flowFile);
                    if (delimiter != null) {
                        final Charset charSet = Charset.forName(context.getProperty(CHARSET).getValue());
                        event = new DelimitedInputStream(in, delimiter.getBytes(charSet));
                    }

                    eventSender.sendEvent(event);
                }
            });

            session.getProvenanceReporter().send(flowFile, transitUri, stopWatch.getElapsed(TimeUnit.MILLISECONDS));
            session.transfer(flowFile, REL_SUCCESS);
        } catch (Exception e) {
            getLogger().error("Exception while handling a process session, transferring {} to failure.", flowFile, e);
            session.transfer(session.penalize(flowFile), REL_FAILURE);
            context.yield();
        }
    }

    @Override
    protected String getProtocol(final ProcessContext context) {
        return TCP_VALUE.getValue();
    }

    @Override
    protected NettyEventSenderFactory<?> getNettyEventSenderFactory(final String hostname, final int port, final String protocol) {
        return new StreamingNettyEventSenderFactory(getLogger(), hostname, port, TransportProtocol.valueOf(protocol));
    }
}
