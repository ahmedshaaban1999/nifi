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
package org.apache.nifi.processors.mongodb;


import org.apache.avro.Schema;
import org.apache.nifi.avro.AvroTypeUtil;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.json.JsonTreeReader;
import org.apache.nifi.mongodb.MongoDBClientService;
import org.apache.nifi.mongodb.MongoDBControllerService;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.schema.access.SchemaAccessUtils;
import org.apache.nifi.serialization.SimpleRecordSchema;
import org.apache.nifi.serialization.record.MapRecord;
import org.apache.nifi.serialization.record.MockRecordParser;
import org.apache.nifi.serialization.record.MockSchemaRegistry;
import org.apache.nifi.serialization.record.RecordField;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.MockProcessContext;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PutMongoRecordIT extends MongoWriteTestBase {

    private MockRecordParser recordReader;

    @BeforeEach
    public void setup() throws Exception {
        super.setup(PutMongoRecord.class);
        recordReader = new MockRecordParser();
    }

    @AfterEach
    public void teardown() {
        super.teardown();
    }

    private TestRunner init() throws InitializationException {
        TestRunner runner = init(PutMongoRecord.class);
        runner.addControllerService("reader", recordReader);
        runner.enableControllerService(recordReader);
        runner.setProperty(PutMongoRecord.RECORD_READER_FACTORY, "reader");
        return runner;
    }

    private byte[] documentToByteArray(Document doc) {
        return doc.toJson().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void testValidators() throws Exception {
        TestRunner runner = TestRunners.newTestRunner(PutMongoRecord.class);
        runner.addControllerService("reader", recordReader);
        runner.enableControllerService(recordReader);
        Collection<ValidationResult> results;
        ProcessContext pc;

        // missing uri, db, collection, RecordReader
        runner.enqueue(new byte[0]);
        pc = runner.getProcessContext();
        results = new HashSet<>();
        if (pc instanceof MockProcessContext) {
            results = ((MockProcessContext) pc).validate();
        }
        assertEquals(3, results.size());
        Iterator<ValidationResult> it = results.iterator();
        Assertions.assertTrue(it.next().toString().contains("is invalid because Mongo Database Name is required"));
        Assertions.assertTrue(it.next().toString().contains("is invalid because Mongo Collection Name is required"));
        Assertions.assertTrue(it.next().toString().contains("is invalid because Record Reader is required"));

        // invalid write concern
        runner.setProperty(AbstractMongoProcessor.URI, MONGO_URI);
        runner.setProperty(AbstractMongoProcessor.DATABASE_NAME, DATABASE_NAME);
        runner.setProperty(AbstractMongoProcessor.COLLECTION_NAME, COLLECTION_NAME);
        runner.setProperty(PutMongoRecord.RECORD_READER_FACTORY, "reader");
        runner.setProperty(PutMongoRecord.WRITE_CONCERN, "xyz");
        runner.enqueue(new byte[0]);
        pc = runner.getProcessContext();
        results = new HashSet<>();
        if (pc instanceof MockProcessContext) {
            results = ((MockProcessContext) pc).validate();
        }
        assertEquals(1, results.size());
        Assertions.assertTrue(results.iterator().next().toString().matches("'Write Concern' .* is invalid because Given value not found in allowed set .*"));

        // valid write concern
        runner.setProperty(PutMongoRecord.WRITE_CONCERN, PutMongoRecord.WRITE_CONCERN_UNACKNOWLEDGED);
        runner.enqueue(new byte[0]);
        pc = runner.getProcessContext();
        results = new HashSet<>();
        if (pc instanceof MockProcessContext) {
            results = ((MockProcessContext) pc).validate();
        }
        assertEquals(0, results.size());
    }

    @Test
    public void testInsertFlatRecords() throws Exception {
        TestRunner runner = init();
        recordReader.addSchemaField("name", RecordFieldType.STRING);
        recordReader.addSchemaField("age", RecordFieldType.INT);
        recordReader.addSchemaField("sport", RecordFieldType.STRING);

        recordReader.addRecord("John Doe", 48, "Soccer");
        recordReader.addRecord("Jane Doe", 47, "Tennis");
        recordReader.addRecord("Sally Doe", 47, "Curling");
        recordReader.addRecord("Jimmy Doe", 14, null);
        recordReader.addRecord("Pizza Doe", 14, null);

        runner.enqueue("");
        runner.run();

        runner.assertAllFlowFilesTransferred(PutMongoRecord.REL_SUCCESS, 1);

        // verify 1 doc inserted into the collection
        assertEquals(5, collection.countDocuments());
        //assertEquals(doc, collection.find().first());


        runner.clearTransferState();

        /*
         * Test it with the client service.
         */
        MongoDBClientService clientService = new MongoDBControllerService();
        runner.addControllerService("clientService", clientService);
        runner.removeProperty(PutMongoRecord.URI);
        runner.setProperty(clientService, MongoDBControllerService.URI, MONGO_URI);
        runner.setProperty(PutMongoRecord.CLIENT_SERVICE, "clientService");
        runner.enableControllerService(clientService);
        runner.assertValid();

        collection.deleteMany(new Document());
        runner.enqueue("");
        runner.run();
        runner.assertAllFlowFilesTransferred(PutMongoRecord.REL_SUCCESS, 1);
        assertEquals(5, collection.countDocuments());
    }

    @Test
    public void testInsertNestedRecords() throws Exception {
        TestRunner runner = init();
        recordReader.addSchemaField("id", RecordFieldType.INT);
        final List<RecordField> personFields = new ArrayList<>();
        final RecordField nameField = new RecordField("name", RecordFieldType.STRING.getDataType());
        final RecordField ageField = new RecordField("age", RecordFieldType.INT.getDataType());
        final RecordField sportField = new RecordField("sport", RecordFieldType.STRING.getDataType());
        personFields.add(nameField);
        personFields.add(ageField);
        personFields.add(sportField);
        final RecordSchema personSchema = new SimpleRecordSchema(personFields);
        recordReader.addSchemaField("person", RecordFieldType.RECORD);
        recordReader.addRecord(1, new MapRecord(personSchema, new HashMap<String,Object>() {{
            put("name", "John Doe");
            put("age", 48);
            put("sport", "Soccer");
        }}));
        recordReader.addRecord(2, new MapRecord(personSchema, new HashMap<String,Object>() {{
            put("name", "Jane Doe");
            put("age", 47);
            put("sport", "Tennis");
        }}));
        recordReader.addRecord(3, new MapRecord(personSchema, new HashMap<String,Object>() {{
            put("name", "Sally Doe");
            put("age", 47);
            put("sport", "Curling");
        }}));
        recordReader.addRecord(4, new MapRecord(personSchema, new HashMap<String,Object>() {{
            put("name", "Jimmy Doe");
            put("age", 14);
            put("sport", null);
        }}));

        runner.enqueue("");
        runner.run();

        runner.assertAllFlowFilesTransferred(PutMongoRecord.REL_SUCCESS, 1);
        MockFlowFile out = runner.getFlowFilesForRelationship(PutMongoRecord.REL_SUCCESS).get(0);


        // verify 1 doc inserted into the collection
        assertEquals(4, collection.countDocuments());
        //assertEquals(doc, collection.find().first());
    }

    @Test
    public void testArrayConversion() throws Exception {
        TestRunner runner = init(PutMongoRecord.class);
        MockSchemaRegistry registry = new MockSchemaRegistry();
        String rawSchema = "{\"type\":\"record\",\"name\":\"Test\",\"fields\":[{\"name\":\"name\",\"type\":\"string\"}," +
                "{\"name\":\"arrayTest\",\"type\":{\"type\":\"array\",\"items\":\"string\"}}]}";
        RecordSchema schema = AvroTypeUtil.createSchema(new Schema.Parser().parse(rawSchema));
        registry.addSchema("test", schema);
        JsonTreeReader reader = new JsonTreeReader();
        runner.addControllerService("registry", registry);
        runner.addControllerService("reader", reader);
        runner.setProperty(reader, SchemaAccessUtils.SCHEMA_REGISTRY, "registry");
        runner.setProperty(PutMongoRecord.RECORD_READER_FACTORY, "reader");
        runner.enableControllerService(registry);
        runner.enableControllerService(reader);
        runner.assertValid();

        Map<String, String> attrs = new HashMap<>();
        attrs.put("schema.name", "test");

        runner.enqueue("{\"name\":\"John Smith\",\"arrayTest\":[\"a\",\"b\",\"c\"]}", attrs);
        runner.run();

        runner.assertTransferCount(PutMongoRecord.REL_FAILURE, 0);
        runner.assertTransferCount(PutMongoRecord.REL_SUCCESS, 1);
    }

    @Test
    void testUpsertAsInsert() throws Exception {
        // GIVEN
        TestRunner runner = init();

        runner.setProperty(PutMongoRecord.UPDATE_KEY_FIELDS, "id");

        recordReader.addSchemaField("id", RecordFieldType.INT);
        recordReader.addSchemaField("person", RecordFieldType.RECORD);

        final RecordSchema personSchema = new SimpleRecordSchema(Arrays.asList(
            new RecordField("name", RecordFieldType.STRING.getDataType()),
            new RecordField("age", RecordFieldType.INT.getDataType())
        ));

        List<List<Object[]>> inputs = Arrays.asList(
            Arrays.asList(
                new Object[]{1, new MapRecord(personSchema, new HashMap<String, Object>() {{
                    put("name", "name1");
                    put("age", 21);
                }})},
                new Object[]{2, new MapRecord(personSchema, new HashMap<String, Object>() {{
                    put("name", "name2");
                    put("age", 22);
                }})}
            )
        );

        Set<Map<String, Object>> expected = new HashSet<>(Arrays.asList(
            new HashMap<String, Object>() {{
                put("id", 1);
                put("person", new Document(new HashMap<String, Object>() {{
                    put("name", "name1");
                    put("age", 21);
                }}));
            }},
            new HashMap<String, Object>() {{
                put("id", 2);
                put("person", new Document(new HashMap<String, Object>() {{
                    put("name", "name2");
                    put("age", 22);
                }}));
            }}
        ));

        // WHEN
        // THEN
        testUpsertSuccess(runner, inputs, expected);
    }

    @Test
    void testUpsertAsUpdate() throws Exception {
        // GIVEN
        TestRunner runner = init();

        runner.setProperty(PutMongoRecord.UPDATE_KEY_FIELDS, "id");

        recordReader.addSchemaField("id", RecordFieldType.INT);
        recordReader.addSchemaField("person", RecordFieldType.RECORD);

        final RecordSchema personSchema = new SimpleRecordSchema(Arrays.asList(
            new RecordField("name", RecordFieldType.STRING.getDataType()),
            new RecordField("age", RecordFieldType.INT.getDataType())
        ));

        List<List<Object[]>> inputs = Arrays.asList(
            Arrays.asList(
                new Object[]{1, new MapRecord(personSchema, new HashMap<String, Object>() {{
                    put("name", "updating_name1");
                    put("age", "age1".length());
                }})},
                new Object[]{2, new MapRecord(personSchema, new HashMap<String, Object>() {{
                    put("name", "name2");
                    put("age", "updating_age2".length());
                }})}
            ),
            Arrays.asList(
                new Object[]{1, new MapRecord(personSchema, new HashMap<String, Object>() {{
                    put("name", "updated_name1");
                    put("age", "age1".length());
                }})},
                new Object[]{2, new MapRecord(personSchema, new HashMap<String, Object>() {{
                    put("name", "name2");
                    put("age", "updated_age2".length());
                }})}
            )
        );

        Set<Map<String, Object>> expected = new HashSet<>(Arrays.asList(
            new HashMap<String, Object>() {{
                put("id", 1);
                put("person", new Document(new HashMap<String, Object>() {{
                    put("name", "updated_name1");
                    put("age", "age1".length());
                }}));
            }},
            new HashMap<String, Object>() {{
                put("id", 2);
                put("person", new Document(new HashMap<String, Object>() {{
                    put("name", "name2");
                    put("age", "updated_age2".length());
                }}));
            }}
        ));

        // WHEN
        testUpsertSuccess(runner, inputs, expected);
    }

    @Test
    void testUpsertAsInsertAndUpdate() throws Exception {
        // GIVEN
        TestRunner runner = init();

        runner.setProperty(PutMongoRecord.UPDATE_KEY_FIELDS, "id");

        recordReader.addSchemaField("id", RecordFieldType.INT);
        recordReader.addSchemaField("person", RecordFieldType.RECORD);

        final RecordSchema personSchema = new SimpleRecordSchema(Arrays.asList(
            new RecordField("name", RecordFieldType.STRING.getDataType()),
            new RecordField("age", RecordFieldType.INT.getDataType())
        ));

        List<List<Object[]>> inputs = Arrays.asList(
            Collections.singletonList(
                new Object[]{1, new MapRecord(personSchema, new HashMap<String, Object>() {{
                    put("name", "updating_name1");
                    put("age", "updating_age1".length());
                }})}
            ),
            Arrays.asList(
                new Object[]{1, new MapRecord(personSchema, new HashMap<String, Object>() {{
                    put("name", "updated_name1");
                    put("age", "updated_age1".length());
                }})},
                new Object[]{2, new MapRecord(personSchema, new HashMap<String, Object>() {{
                    put("name", "inserted_name2");
                    put("age", "inserted_age2".length());
                }})}
            )
        );

        Set<Map<String, Object>> expected = new HashSet<>(Arrays.asList(
            new HashMap<String, Object>() {{
                put("id", 1);
                put("person", new Document(new HashMap<String, Object>() {{
                    put("name", "updated_name1");
                    put("age", "updated_age1".length());
                }}));
            }},
            new HashMap<String, Object>() {{
                put("id", 2);
                put("person", new Document(new HashMap<String, Object>() {{
                    put("name", "inserted_name2");
                    put("age", "inserted_age2".length());
                }}));
            }}
        ));

        // WHEN
        testUpsertSuccess(runner, inputs, expected);
    }

    @Test
    void testRouteToFailureWhenKeyFieldDoesNotExist() throws Exception {
        // GIVEN
        TestRunner runner = init();

        runner.setProperty(PutMongoRecord.UPDATE_KEY_FIELDS, "id,non_existent_field");

        recordReader.addSchemaField("id", RecordFieldType.INT);
        recordReader.addSchemaField("person", RecordFieldType.RECORD);

        final RecordSchema personSchema = new SimpleRecordSchema(Arrays.asList(
            new RecordField("name", RecordFieldType.STRING.getDataType()),
            new RecordField("age", RecordFieldType.INT.getDataType())
        ));

        List<List<Object[]>> inputs = Arrays.asList(
            Collections.singletonList(
                new Object[]{1, new MapRecord(personSchema, new HashMap<String, Object>() {{
                    put("name", "unimportant");
                    put("age", "unimportant".length());
                }})}
            )
        );

        // WHEN
        // THEN
        testUpsertFailure(runner, inputs);
    }

    @Test
    void testUpdateMany() throws Exception {
        // GIVEN
        TestRunner initRunner = init();

        // Init Mongo data
        recordReader.addSchemaField("name", RecordFieldType.STRING);
        recordReader.addSchemaField("team", RecordFieldType.STRING);
        recordReader.addSchemaField("color", RecordFieldType.STRING);

        List<Object[]> init = Arrays.asList(
            new Object[]{"Joe", "A", "green"},
            new Object[]{"Jane", "A", "green"},
            new Object[]{"Jeff", "B", "blue"},
            new Object[]{"Janet", "B", "blue"}
        );

        init.forEach(recordReader::addRecord);

        initRunner.enqueue("");
        initRunner.run();

        // Update Mongo data
        setup();
        TestRunner updateRunner = init();

        updateRunner.setProperty(PutMongoRecord.UPDATE_KEY_FIELDS, "team");
        updateRunner.setProperty(PutMongoRecord.UPDATE_MODE, PutMongoRecord.UPDATE_MANY.getValue());

        recordReader.addSchemaField("team", RecordFieldType.STRING);
        recordReader.addSchemaField("color", RecordFieldType.STRING);

        List<List<Object[]>> inputs = Arrays.asList(
            Arrays.asList(
                new Object[]{"A", "yellow"},
                new Object[]{"B", "red"}
            )
        );

        Set<Map<String, Object>> expected = new HashSet<>(Arrays.asList(
            new HashMap<String, Object>() {{
                put("name", "Joe");
                put("team", "A");
                put("color", "yellow");
            }},
            new HashMap<String, Object>() {{
                put("name", "Jane");
                put("team", "A");
                put("color", "yellow");
            }},
            new HashMap<String, Object>() {{
                put("name", "Jeff");
                put("team", "B");
                put("color", "red");
            }},
            new HashMap<String, Object>() {{
                put("name", "Janet");
                put("team", "B");
                put("color", "red");
            }}
        ));

        // WHEN
        // THEN
        testUpsertSuccess(updateRunner, inputs, expected);
    }

    @Test
    void testUpdateModeFFAttributeSetToMany() throws Exception {
        // GIVEN
        TestRunner initRunner = init();

        // Init Mongo data
        recordReader.addSchemaField("name", RecordFieldType.STRING);
        recordReader.addSchemaField("team", RecordFieldType.STRING);
        recordReader.addSchemaField("color", RecordFieldType.STRING);

        List<Object[]> init = Arrays.asList(
            new Object[]{"Joe", "A", "green"},
            new Object[]{"Jane", "A", "green"},
            new Object[]{"Jeff", "B", "blue"},
            new Object[]{"Janet", "B", "blue"}
        );

        init.forEach(recordReader::addRecord);

        initRunner.enqueue("");
        initRunner.run();

        // Update Mongo data
        setup();
        TestRunner updateRunner = init();

        updateRunner.setProperty(PutMongoRecord.UPDATE_KEY_FIELDS, "team");
        updateRunner.setProperty(PutMongoRecord.UPDATE_MODE, PutMongoRecord.UPDATE_FF_ATTRIBUTE.getValue());

        recordReader.addSchemaField("team", RecordFieldType.STRING);
        recordReader.addSchemaField("color", RecordFieldType.STRING);

        List<List<Object[]>> inputs = Arrays.asList(
            Arrays.asList(
                new Object[]{"A", "yellow"},
                new Object[]{"B", "red"}
            )
        );

        Set<Map<String, Object>> expected = new HashSet<>(Arrays.asList(
            new HashMap<String, Object>() {{
                put("name", "Joe");
                put("team", "A");
                put("color", "yellow");
            }},
            new HashMap<String, Object>() {{
                put("name", "Jane");
                put("team", "A");
                put("color", "yellow");
            }},
            new HashMap<String, Object>() {{
                put("name", "Jeff");
                put("team", "B");
                put("color", "red");
            }},
            new HashMap<String, Object>() {{
                put("name", "Janet");
                put("team", "B");
                put("color", "red");
            }}
        ));

        // WHEN
        inputs.forEach(input -> {
            input.forEach(recordReader::addRecord);

            MockFlowFile flowFile = new MockFlowFile(1);
            flowFile.putAttributes(new HashMap<String, String>(){{
                put(PutMongoRecord.MONGODB_UPDATE_MODE, "many");
            }});
            updateRunner.enqueue(flowFile);
            updateRunner.run();
        });

        // THEN
        assertEquals(0, updateRunner.getQueueSize().getObjectCount());

        updateRunner.assertAllFlowFilesTransferred(PutMongoRecord.REL_SUCCESS, inputs.size());

        Set<Map<String, Object>> actual = new HashSet<>();
        for (Document document : collection.find()) {
            actual.add(document.entrySet().stream()
                .filter(key__value -> !key__value.getKey().equals("_id"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        }

        assertEquals(expected, actual);
    }

    @Test
    void testRouteToFailureWhenUpdateModeFFAttributeSetToInvalid() throws Exception {
        TestRunner runner = init();

        runner.setProperty(PutMongoRecord.UPDATE_KEY_FIELDS, "team");
        runner.setProperty(PutMongoRecord.UPDATE_MODE, PutMongoRecord.UPDATE_FF_ATTRIBUTE.getValue());

        recordReader.addSchemaField("team", RecordFieldType.STRING);
        recordReader.addSchemaField("color", RecordFieldType.STRING);

        List<List<Object[]>> inputs = Arrays.asList(
            Arrays.asList(
                new Object[]{"A", "yellow"},
                new Object[]{"B", "red"}
            )
        );

        // WHEN
        // THEN
        testUpsertFailure(runner, inputs);
    }

    @Test
    void testRouteToFailureWhenKeyFieldReferencesNonEmbeddedDocument() throws Exception {
        // GIVEN
        TestRunner runner = init();

        runner.setProperty(PutMongoRecord.UPDATE_KEY_FIELDS, "id,id.is_not_an_embedded_document");

        recordReader.addSchemaField("id", RecordFieldType.INT);
        recordReader.addSchemaField("person", RecordFieldType.RECORD);

        final RecordSchema personSchema = new SimpleRecordSchema(Arrays.asList(
            new RecordField("name", RecordFieldType.STRING.getDataType()),
            new RecordField("age", RecordFieldType.INT.getDataType())
        ));

        List<List<Object[]>> inputs = Arrays.asList(
            Collections.singletonList(
                new Object[]{1, new MapRecord(personSchema, new HashMap<String, Object>() {{
                    put("name", "unimportant");
                    put("age", "unimportant".length());
                }})}
            )
        );

        // WHEN
        // THEN
        testUpsertFailure(runner, inputs);
    }

    private void testUpsertSuccess(TestRunner runner, List<List<Object[]>> inputs, Set<Map<String, Object>> expected) {
        // GIVEN

        // WHEN
        inputs.forEach(input -> {
            input.forEach(recordReader::addRecord);

            runner.enqueue("");
            runner.run();
        });

        // THEN
        assertEquals(0, runner.getQueueSize().getObjectCount());

        runner.assertAllFlowFilesTransferred(PutMongoRecord.REL_SUCCESS, inputs.size());

        Set<Map<String, Object>> actual = new HashSet<>();
        for (Document document : collection.find()) {
            actual.add(document.entrySet().stream()
                .filter(key__value -> !key__value.getKey().equals("_id"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        }

        assertEquals(expected, actual);
    }

    private void testUpsertFailure(TestRunner runner, List<List<Object[]>> inputs) {
        // GIVEN
        Set<Object> expected = Collections.emptySet();

        // WHEN
        inputs.forEach(input -> {
            input.forEach(recordReader::addRecord);

            runner.enqueue("");
            runner.run();
        });

        // THEN
        assertEquals(0, runner.getQueueSize().getObjectCount());

        runner.assertAllFlowFilesTransferred(PutMongoRecord.REL_FAILURE, inputs.size());

        Set<Map<String, Object>> actual = new HashSet<>();
        for (Document document : collection.find()) {
            actual.add(document.entrySet().stream()
                .filter(key__value -> !key__value.getKey().equals("_id"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        }

        assertEquals(expected, actual);
    }
}
