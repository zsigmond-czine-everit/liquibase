package liquibase.snapshot;

import liquibase.CatalogAndSchema;
import liquibase.database.Database;
import liquibase.database.DatabaseConnection;
import liquibase.database.OfflineConnection;
import liquibase.exception.DatabaseException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.parser.core.ParsedNode;
import liquibase.parser.core.ParsedNodeException;
import liquibase.resource.ResourceAccessor;
import liquibase.serializer.LiquibaseSerializable;
import liquibase.structure.DatabaseObject;
import liquibase.structure.DatabaseObjectCollection;
import liquibase.structure.core.*;
import liquibase.diff.compare.DatabaseObjectComparatorFactory;
import liquibase.util.ISODateFormat;
import liquibase.util.ObjectUtil;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public abstract class DatabaseSnapshot implements LiquibaseSerializable {

    private final DatabaseObject[] originalExamples;
    private HashSet<String> serializableFields;
    private SnapshotControl snapshotControl;
    private Database database;
    private DatabaseObjectCollection allFound;
    private DatabaseObjectCollection referencedObjects;
    private Map<Class<? extends DatabaseObject>, Set<DatabaseObject>> knownNull = new HashMap<Class<? extends DatabaseObject>, Set<DatabaseObject>>();

    private Map<String, Object> snapshotScratchPad = new HashMap<String, Object>();

    private Map<String, ResultSetCache> resultSetCaches = new HashMap<String, ResultSetCache>();

    DatabaseSnapshot(DatabaseObject[] examples, Database database, SnapshotControl snapshotControl) throws DatabaseException, InvalidExampleException {
        this.database = database;
        allFound = new DatabaseObjectCollection(database);
        referencedObjects = new DatabaseObjectCollection(database);
        this.snapshotControl = snapshotControl;

        this.originalExamples = examples;

        init(examples);

        this.serializableFields = new HashSet<String>();
        this.serializableFields.add("snapshotControl");
        this.serializableFields.add("objects");
        this.serializableFields.add("referencedObjects");
        this.serializableFields.add("database");
        this.serializableFields.add("created");
    }

    protected void init(DatabaseObject[] examples) throws DatabaseException, InvalidExampleException {
        if (examples != null) {
            Set<Catalog> catalogs = new HashSet<Catalog>();
            for (DatabaseObject object : examples) {
                if (object instanceof Schema) {
                    catalogs.add(((Schema) object).getCatalog());
                }
            }

            for (Catalog catalog : catalogs) {
                this.snapshotControl.addType(catalog.getClass(), database);
                include(catalog);
            }
            for (DatabaseObject obj : examples) {
                this.snapshotControl.addType(obj.getClass(), database);

                include(obj);
            }
        }
    }

    public DatabaseSnapshot(DatabaseObject[] examples, Database database) throws DatabaseException, InvalidExampleException {
        this(examples, database, new SnapshotControl(database));
    }

    public SnapshotControl getSnapshotControl() {
        return snapshotControl;
    }

    @Override
    public String getSerializedObjectName() {
        return "snapshot";
    }

    @Override
    public String getSerializedObjectNamespace() {
        return STANDARD_SNAPSHOT_NAMESPACE;
    }

    @Override
    public String getSerializableFieldNamespace(String field) {
        return getSerializedObjectNamespace();
    }


    @Override
    public Set<String> getSerializableFields() {
        return serializableFields;
    }

    @Override
    public Object getSerializableFieldValue(String field) {
        if (field.equals("snapshotControl")) {
            return snapshotControl;
        } else if (field.equals("objects")) {
            return allFound;
        } else if (field.equals("referencedObjects")) {
            return referencedObjects;
        } else if (field.equals("created")) {
            return new ISODateFormat().format(new Timestamp(new Date().getTime()));
        } else if (field.equals("database")) {
            Map<String, Object> map = new HashMap<String, Object>();
            map.put("shortName", database.getShortName());
            map.put("productName", database.getDatabaseProductName());
            map.put("url", database.getConnection().getURL());
            try {
                map.put("majorVersion", database.getDatabaseMajorVersion());
                map.put("minorVersion", database.getDatabaseMinorVersion());
                map.put("productVersion", database.getDatabaseProductVersion());
                map.put("user", database.getConnection().getConnectionUserName());
            } catch (DatabaseException e) {
                //ok
            }
            return map;
        } else {
            throw new UnexpectedLiquibaseException("Unknown field: " + field);
        }
    }

    @Override
    public SerializationType getSerializableFieldType(String field) {
        if (field.equals("snapshotControl")) {
            return SerializationType.NESTED_OBJECT;
        } else if (field.equals("objects")) {
            return SerializationType.NESTED_OBJECT;
        } else if (field.equals("referencedObjects")) {
            return SerializationType.NESTED_OBJECT;
        } else {
            throw new UnexpectedLiquibaseException("Unknown field: " + field);
        }
    }

    public Database getDatabase() {
        return database;
    }

    public ResultSetCache getResultSetCache(String key) {
        if (!resultSetCaches.containsKey(key)) {
            resultSetCaches.put(key, new ResultSetCache());
        }
        return resultSetCaches.get(key);
    }

    /**
     * Include the object described by the passed example object in this snapshot. Returns the object snapshot or null if the object does not exist in the database.
     * If the same object was returned by an earlier include() call, the same object instance will be returned.
     */
    protected <T extends DatabaseObject> T include(T example) throws DatabaseException, InvalidExampleException {
        if (example == null) {
            return null;
        }

        if (database.isSystemObject(example)) {
            return null;
        }

        if (example instanceof Schema && example.getName() == null && (((Schema) example).getCatalog() == null || ((Schema) example).getCatalogName() == null)) {
            CatalogAndSchema catalogAndSchema = ((Schema) example).toCatalogAndSchema().customize(database);
            example = (T) new Schema(catalogAndSchema.getCatalogName(), catalogAndSchema.getSchemaName());
        }

        if (!snapshotControl.shouldInclude(example.getClass())) {
            return example;
        }

        T existing = get(example);
        if (existing != null) {
            return existing;
        }
        if (isKnownNull(example)) {
            return null;
        }

        SnapshotListener snapshotListener = snapshotControl.getSnapshotListener();

        SnapshotGeneratorChain chain = createGeneratorChain(example.getClass(), database);
        if (snapshotListener != null) {
            snapshotListener.willSnapshot(example, database);
        }

        T object = chain.snapshot(example, this);

        if (object == null) {
            Set<DatabaseObject> collection = knownNull.get(example.getClass());
            if (collection == null) {
                collection = new HashSet<DatabaseObject>();
                knownNull.put(example.getClass(), collection);
            }
            collection.add(example);

        } else {
            allFound.add(object);

            try {
                includeNestedObjects(object);
            } catch (InstantiationException e) {
                throw new UnexpectedLiquibaseException(e);
            } catch (IllegalAccessException e) {
                throw new UnexpectedLiquibaseException(e);
            }
        }

        if (snapshotListener != null) {
            snapshotListener.finishedSnapshot(example, object, database);
        }

        return object;
    }

    private void includeNestedObjects(DatabaseObject object) throws DatabaseException, InvalidExampleException, InstantiationException, IllegalAccessException {
        for (String field : new HashSet<String>(object.getAttributes())) {
            if (object.getClass() == Index.class && field.equals("columns")) {
                continue;
            }
            if (object.getClass() == PrimaryKey.class && field.equals("columns")) {
                continue;
            }
            if (object.getClass() == UniqueConstraint.class && field.equals("columns")) {
                continue;
            }
            Object fieldValue = object.getAttribute(field, Object.class);
            Object newFieldValue = replaceObject(fieldValue);
            if (newFieldValue == null) { //sometimes an object references a non-snapshotted object. Leave it with the unsnapshotted example
                if (object instanceof PrimaryKey && field.equals("backingIndex")) { //unless it is the backing index, that is handled a bit strange and we need to handle the case where there is no backing index (disabled PK on oracle)
                    object.setAttribute(field, null);
                }
            } else if (fieldValue != newFieldValue) {
                object.setAttribute(field, newFieldValue);
            }
        }
    }

    private Object replaceObject(Object fieldValue) throws DatabaseException, InvalidExampleException, IllegalAccessException, InstantiationException {
        if (fieldValue == null) {
            return null;
        }
        if (fieldValue instanceof DatabaseObject) {
            if (((DatabaseObject) fieldValue).getSnapshotId() != null) { //already been replaced
                return fieldValue;
            }

            if (!snapshotControl.shouldInclude(((DatabaseObject) fieldValue).getClass())) {
                return fieldValue;
            }

            if (isWrongSchema(((DatabaseObject) fieldValue))) {
                DatabaseObject savedFieldValue = referencedObjects.get((DatabaseObject) fieldValue);
                if (savedFieldValue == null) {
                    savedFieldValue = (DatabaseObject) fieldValue;
                    savedFieldValue.setSnapshotId(SnapshotIdService.getInstance().generateId());
                    includeNestedObjects(savedFieldValue);

                    referencedObjects.add(savedFieldValue);
                }

                return savedFieldValue;
            }

            if (((DatabaseObject) fieldValue).getSnapshotId() == null) {
                return include((DatabaseObject) fieldValue);
            } else {
                return fieldValue;
            }
            //            } else if (Set.class.isAssignableFrom(field.getType())) {
            //                field.setAccessible(true);
            //                Set fieldValue = field.get(object);
            //                for (Object val : fieldValue) {
            //
            //                }
        } else if (fieldValue instanceof Collection) {
            Iterator fieldValueIterator = new CopyOnWriteArrayList((Collection) fieldValue).iterator();
            List newValues = new ArrayList();
            while (fieldValueIterator.hasNext()) {
                Object obj = fieldValueIterator.next();
                if (fieldValue instanceof DatabaseObject && !snapshotControl.shouldInclude(((DatabaseObject) fieldValue).getClass())) {
                    return fieldValue;
                }

                if (obj instanceof DatabaseObject && ((DatabaseObject) obj).getSnapshotId() == null) {
                    obj = include((DatabaseObject) obj);
                }
                if (obj != null) {
                    newValues.add(obj);
                }
            }
            Collection newCollection = null;
            try {
                Class<?> collectionClass = fieldValue.getClass();
                if (List.class.isAssignableFrom(collectionClass)) {
                    collectionClass = ArrayList.class;
                }
                newCollection = (Collection) collectionClass.newInstance();
            } catch (InstantiationException e) {
                throw e;
            }
            newCollection.addAll(newValues);
            return newCollection;
        } else if (fieldValue instanceof Map) {
            Map newMap = (Map) fieldValue.getClass().newInstance();
            for (Map.Entry entry : new HashSet<Map.Entry>((Set<Map.Entry>) ((Map) fieldValue).entrySet())) {
                Object key = replaceObject(entry.getKey());
                Object value = replaceObject(entry.getValue());

                if (key != null) {
                    newMap.put(key, value);
                }
            }

            return newMap;

        }

        return fieldValue;
    }

    protected boolean isWrongSchema(DatabaseObject fieldValue) {
        boolean isSchemaExamples = true;
        if (originalExamples == null) {
            return false;
        }
        for (DatabaseObject obj : originalExamples) {
            if (!(obj instanceof Schema)) {
                isSchemaExamples = false;
                break;
            }
        }

        if (!isSchemaExamples) {
            return false;
        }

        for (DatabaseObject obj : originalExamples) {
            if (DatabaseObjectComparatorFactory.getInstance().isSameObject(fieldValue.getSchema(), obj, database)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the object described by the passed example if it is already included in this snapshot.
     */
    public <DatabaseObjectType extends DatabaseObject> DatabaseObjectType get(DatabaseObjectType example) {
        return allFound.get(example);
    }

    /**
     * Returns all objects of the given type that are already included in this snapshot.
     */
    public <DatabaseObjectType extends DatabaseObject> Set<DatabaseObjectType> get(Class<DatabaseObjectType> type) {
        return allFound.get(type);
    }


    protected SnapshotGeneratorChain createGeneratorChain(Class<? extends DatabaseObject> databaseObjectType, Database database) {
        SortedSet<SnapshotGenerator> generators = SnapshotGeneratorFactory.getInstance().getGenerators(databaseObjectType, database);
        if (generators == null || generators.size() == 0) {
            return null;
        }
        //noinspection unchecked
        return new SnapshotGeneratorChain(generators);
    }

    private boolean isKnownNull(DatabaseObject example) {
        Set<DatabaseObject> databaseObjects = knownNull.get(example.getClass());
        if (databaseObjects == null) {
            return false;
        }
        for (DatabaseObject obj : databaseObjects) {
            if (DatabaseObjectComparatorFactory.getInstance().isSameObject(obj, example, database)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void load(ParsedNode parsedNode, ResourceAccessor resourceAccessor) throws ParsedNodeException {
        try {
            Map<String, DatabaseObject> referencedObjects = new HashMap<String, DatabaseObject>();
            Map<String, DatabaseObject> objects = new HashMap<String, DatabaseObject>();
            Map<String, DatabaseObject> allObjects = new HashMap<String, DatabaseObject>();
            ParsedNode databaseNode = parsedNode.getChild(null, "database");
            DatabaseConnection connection = getDatabase().getConnection();
            if (databaseNode != null && connection instanceof OfflineConnection) {
                ((OfflineConnection) connection).setDatabaseMajorVersion(databaseNode.getChildValue(null, "majorVersion", Integer.class));
                ((OfflineConnection) connection).setDatabaseMinorVersion(databaseNode.getChildValue(null, "minorVersion", Integer.class));
                ((OfflineConnection) connection).setProductVersion(databaseNode.getChildValue(null, "productVersion", String.class));
                ((OfflineConnection) connection).setConnectionUserName(databaseNode.getChildValue(null, "user", String.class));
            }

            loadObjects(referencedObjects, allObjects, parsedNode.getChild(null, "referencedObjects"), resourceAccessor);
            loadObjects(objects, allObjects, parsedNode.getChild(null, "objects"), resourceAccessor);

            for (DatabaseObject object : allObjects.values()) {
                for (String attr : new ArrayList<String>(object.getAttributes())) {
                    Object value = object.getAttribute(attr, Object.class);
                    if (value instanceof String && allObjects.containsKey(value)) {
                        if (ObjectUtil.hasProperty(object, attr)) {
                            ObjectUtil.setProperty(object, attr, allObjects.get(value));
                        } else {
                            object.setAttribute(attr, allObjects.get(value));
                        }
                    } else if (value instanceof Collection && ((Collection) value).size() > 0 && allObjects.containsKey(((Collection) value).iterator().next())) {
                        List newList = new ArrayList();
                        for (String element : (Collection<String>) value) {
                            newList.add(allObjects.get(element));
                        }
                        if (ObjectUtil.hasProperty(object, attr)) {
                            ObjectUtil.setProperty(object, attr, newList);
                        } else {
                            object.setAttribute(attr, newList);
                        }
                    } else {
                        if (value != null && ObjectUtil.hasProperty(object, attr)) {
                            object.setAttribute(attr, null);
                            ObjectUtil.setProperty(object, attr, value);
                        }
                    }
                }
            }

            for (DatabaseObject object : objects.values()) {
                this.allFound.add(object);
            }
            for (DatabaseObject object : referencedObjects.values()) {
                this.referencedObjects.add(object);
            }
        } catch (Exception e) {
            throw new ParsedNodeException(e);
        }
    }

    protected void loadObjects(Map<String, DatabaseObject> objectMap, Map<String, DatabaseObject> allObjects, ParsedNode node, ResourceAccessor resourceAccessor) throws ClassNotFoundException, InstantiationException, IllegalAccessException, ParsedNodeException {
        if (node == null) {
            return;
        }
        for (ParsedNode typeNode : node.getChildren()) {
            Class<? extends DatabaseObject> objectType = (Class<? extends DatabaseObject>) Class.forName(typeNode.getName());
            for (ParsedNode objectNode : typeNode.getChildren()) {
                DatabaseObject databaseObject = objectType.newInstance();
                databaseObject.load(objectNode, resourceAccessor);
                String key = objectType.getName() + "#" + databaseObject.getSnapshotId();
                objectMap.put(key, databaseObject);
                allObjects.put(key, databaseObject);
            }
        }
    }

    @Override
    public ParsedNode serialize() {
        throw new RuntimeException("TODO");
    }

    /**
     * Used to get and store misc data that should be scoped to the snapshot. Helpful for caching snapshot results.
     * @deprecated Will be removed with 4.0
     */
    public Object getScratchData(String key) {
        return snapshotScratchPad.get(key);
    }

    public Object setScratchData(String key, Object data) {
        return snapshotScratchPad.put(key, data);
    }
}
