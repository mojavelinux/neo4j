/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.nioneo.xa;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.transaction.xa.XAException;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.helpers.Pair;
import org.neo4j.kernel.api.KernelAPI;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.labelscan.LabelScanStore;
import org.neo4j.kernel.api.labelscan.NodeLabelUpdate;
import org.neo4j.kernel.api.labelscan.NodeLabelUpdateNodeIdComparator;
import org.neo4j.kernel.api.properties.DefinedProperty;
import org.neo4j.kernel.api.properties.Property;
import org.neo4j.kernel.impl.api.KernelTransactionImplementation;
import org.neo4j.kernel.impl.api.index.IndexingService;
import org.neo4j.kernel.impl.core.CacheAccessBackDoor;
import org.neo4j.kernel.impl.core.DenseNodeChainPosition;
import org.neo4j.kernel.impl.core.RelationshipLoadingPosition;
import org.neo4j.kernel.impl.core.SingleChainPosition;
import org.neo4j.kernel.impl.core.Token;
import org.neo4j.kernel.impl.core.TransactionState;
import org.neo4j.kernel.impl.locking.LockGroup;
import org.neo4j.kernel.impl.locking.LockService;
import org.neo4j.kernel.impl.nioneo.store.DynamicRecord;
import org.neo4j.kernel.impl.nioneo.store.IndexRule;
import org.neo4j.kernel.impl.nioneo.store.InvalidRecordException;
import org.neo4j.kernel.impl.nioneo.store.LabelTokenRecord;
import org.neo4j.kernel.impl.nioneo.store.LabelTokenStore;
import org.neo4j.kernel.impl.nioneo.store.NeoStore;
import org.neo4j.kernel.impl.nioneo.store.NeoStoreRecord;
import org.neo4j.kernel.impl.nioneo.store.NodeRecord;
import org.neo4j.kernel.impl.nioneo.store.NodeStore;
import org.neo4j.kernel.impl.nioneo.store.PrimitiveRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyBlock;
import org.neo4j.kernel.impl.nioneo.store.PropertyKeyTokenRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyKeyTokenStore;
import org.neo4j.kernel.impl.nioneo.store.PropertyRecord;
import org.neo4j.kernel.impl.nioneo.store.PropertyStore;
import org.neo4j.kernel.impl.nioneo.store.PropertyType;
import org.neo4j.kernel.impl.nioneo.store.Record;
import org.neo4j.kernel.impl.nioneo.store.RelationshipGroupRecord;
import org.neo4j.kernel.impl.nioneo.store.RelationshipGroupStore;
import org.neo4j.kernel.impl.nioneo.store.RelationshipRecord;
import org.neo4j.kernel.impl.nioneo.store.RelationshipStore;
import org.neo4j.kernel.impl.nioneo.store.RelationshipTypeTokenRecord;
import org.neo4j.kernel.impl.nioneo.store.RelationshipTypeTokenStore;
import org.neo4j.kernel.impl.nioneo.store.SchemaRule;
import org.neo4j.kernel.impl.nioneo.store.SchemaStore;
import org.neo4j.kernel.impl.nioneo.store.UnderlyingStorageException;
import org.neo4j.kernel.impl.nioneo.store.labels.NodeLabels;
import org.neo4j.kernel.impl.nioneo.xa.Command.NodeCommand;
import org.neo4j.kernel.impl.nioneo.xa.Command.RelationshipGroupCommand;
import org.neo4j.kernel.impl.nioneo.xa.Command.SchemaRuleCommand;
import org.neo4j.kernel.impl.nioneo.xa.RecordChanges.RecordChange;
import org.neo4j.kernel.impl.transaction.xaframework.XaCommand;
import org.neo4j.kernel.impl.transaction.xaframework.XaLogicalLog;
import org.neo4j.kernel.impl.transaction.xaframework.XaTransaction;
import org.neo4j.kernel.impl.util.ArrayMap;
import org.neo4j.kernel.impl.util.PrimitiveLongIterator;
import org.neo4j.kernel.impl.util.RelIdArray.DirectionWrapper;
import org.neo4j.unsafe.batchinsert.LabelScanWriter;

import static java.util.Arrays.binarySearch;
import static java.util.Arrays.copyOf;
import static org.neo4j.helpers.collection.IteratorUtil.asPrimitiveIterator;
import static org.neo4j.helpers.collection.IteratorUtil.first;
import static org.neo4j.kernel.impl.nioneo.store.PropertyStore.encodeString;
import static org.neo4j.kernel.impl.nioneo.store.labels.NodeLabelsField.parseLabelsField;
import static org.neo4j.kernel.impl.nioneo.xa.Command.Mode.CREATE;
import static org.neo4j.kernel.impl.nioneo.xa.Command.Mode.DELETE;
import static org.neo4j.kernel.impl.nioneo.xa.Command.Mode.UPDATE;

/**
 * Transaction containing {@link Command commands} reflecting the operations
 * performed in the transaction.
 *
 * This class currently has a symbiotic relationship with {@link KernelTransaction}, with which it always has a 1-1
 * relationship.
 *
 * The idea here is that KernelTransaction will eventually take on the responsibilities of WriteTransaction, such as
 * keeping track of transaction state, serialization and deserialization to and from logical log, and applying things
 * to store. It would most likely do this by keeping a component derived from the current WriteTransaction
 * implementation as a sub-component, responsible for handling logical log commands.
 *
 * The class XAResourceManager plays in here as well, in that it shares responsibilities with WriteTransaction to
 * write data to the logical log. As we continue to refactor this subsystem, XAResourceManager should ideally not know
 * about the logical log, but defer entirely to the Kernel to handle this. Doing that will give the kernel full
 * discretion to start experimenting with higher-performing logical log implementations, without being hindered by
 * having to contend with the JTA compliance layers. In short, it would encapsulate the logical log/storage logic better
 * and thus make it easier to change.
 */
public class NeoStoreTransaction extends XaTransaction
{
    private final RecordChanges<Long, NodeRecord, Void> nodeRecords =
            new RecordChanges<>( new RecordChanges.Loader<Long, NodeRecord, Void>()
            {
                @Override
                public NodeRecord newUnused( Long key, Void additionalData )
                {
                    return new NodeRecord( key, false, Record.NO_NEXT_RELATIONSHIP.intValue(),
                                           Record.NO_NEXT_PROPERTY.intValue() );
                }

                @Override
                public NodeRecord load( Long key, Void additionalData )
                {
                    return getNodeStore().getRecord( key );
                }

                @Override
                public void ensureHeavy( NodeRecord record )
                {
                    getNodeStore().ensureHeavy( record );
                }

                @Override
                public NodeRecord clone(NodeRecord nodeRecord)
                {
                    return nodeRecord.clone();
                }
            }, true );
    private final RecordChanges<Long, PropertyRecord, PrimitiveRecord> propertyRecords =
            new RecordChanges<>( new RecordChanges.Loader<Long, PropertyRecord, PrimitiveRecord>()
            {
                @Override
                public PropertyRecord newUnused( Long key, PrimitiveRecord additionalData )
                {
                    PropertyRecord record = new PropertyRecord( key );
                    setOwner( record, additionalData );
                    return record;
                }

                private void setOwner( PropertyRecord record, PrimitiveRecord owner )
                {
                    if ( owner != null )
                    {
                        owner.setIdTo( record );
                    }
                }

                @Override
                public PropertyRecord load( Long key, PrimitiveRecord additionalData )
                {
                    PropertyRecord record = getPropertyStore().getRecord( key.longValue() );
                    setOwner( record, additionalData );
                    return record;
                }

                @Override
                public void ensureHeavy( PropertyRecord record )
                {
                    for ( PropertyBlock block : record.getPropertyBlocks() )
                    {
                        getPropertyStore().ensureHeavy( block );
                    }
                }

                @Override
                public PropertyRecord clone(PropertyRecord propertyRecord)
                {
                    return propertyRecord.clone();
                }
            }, true );
    private final RecordChanges<Long, RelationshipRecord, Void> relRecords =
            new RecordChanges<>( new RecordChanges.Loader<Long, RelationshipRecord, Void>()
            {
                @Override
                public RelationshipRecord newUnused( Long key, Void additionalData )
                {
                    return new RelationshipRecord( key );
                }

                @Override
                public RelationshipRecord load( Long key, Void additionalData )
                {
                    return getRelationshipStore().getRecord( key );
                }

                @Override
                public void ensureHeavy( RelationshipRecord record )
                {
                }

                @Override
                public RelationshipRecord clone(RelationshipRecord relationshipRecord) {
                    // Not needed because we don't manage before state for relationship records.
                    throw new UnsupportedOperationException("Unexpected call to clone on a relationshipRecord");
                }
            }, false );
    private final RecordChanges<Long, RelationshipGroupRecord, Integer> relGroupRecords =
            new RecordChanges<>( new RecordChanges.Loader<Long, RelationshipGroupRecord, Integer>()
            {
                @Override
                public RelationshipGroupRecord newUnused( Long key, Integer type )
                {
                    return new RelationshipGroupRecord( key, type );
                }

                @Override
                public RelationshipGroupRecord load( Long key, Integer type )
                {
                    return neoStore.getRelationshipGroupStore().getRecord( key );
                }

                @Override
                public void ensureHeavy( RelationshipGroupRecord record )
                {   // Not needed
                }

                @Override
                public RelationshipGroupRecord clone( RelationshipGroupRecord record )
                {
                    throw new UnsupportedOperationException();
                }
            }, false );
    private final RecordChanges<Long, Collection<DynamicRecord>, SchemaRule> schemaRuleChanges =
            new RecordChanges<>(new RecordChanges.Loader<Long, Collection<DynamicRecord>, SchemaRule>()
            {
            @Override
            public Collection<DynamicRecord> newUnused(Long key, SchemaRule additionalData)
            {
                return getSchemaStore().allocateFrom(additionalData);
            }
    
            @Override
            public Collection<DynamicRecord> load(Long key, SchemaRule additionalData)
            {
                return getSchemaStore().getRecords( key );
            }
    
            @Override
            public void ensureHeavy(Collection<DynamicRecord> dynamicRecords)
            {
                SchemaStore schemaStore = getSchemaStore();
                for ( DynamicRecord record : dynamicRecords)
                {
                    schemaStore.ensureHeavy(record);
                }
            }
    
            @Override
            public Collection<DynamicRecord> clone(Collection<DynamicRecord> dynamicRecords) {
                Collection<DynamicRecord> list = new ArrayList<>( dynamicRecords.size() );
                for ( DynamicRecord record : dynamicRecords)
                {
                    list.add( record.clone() );
                }
                return list;
            }
        }, true);
    private Map<Integer, RelationshipTypeTokenRecord> relationshipTypeTokenRecords;
    private Map<Integer, LabelTokenRecord> labelTokenRecords;
    private Map<Integer, PropertyKeyTokenRecord> propertyKeyTokenRecords;
    private final Map<Long, Map<Integer, RelationshipGroupRecord>> relGroupCache = new HashMap<>();
    private RecordChanges<Long, NeoStoreRecord, Void> neoStoreRecord;

    private final Map<Long, Command.NodeCommand> nodeCommands = new TreeMap<>();
    private final ArrayList<Command.PropertyCommand> propCommands = new ArrayList<>();
    private final ArrayList<Command.RelationshipCommand> relCommands = new ArrayList<>();
    private final ArrayList<Command.SchemaRuleCommand> schemaRuleCommands = new ArrayList<>();
    private final ArrayList<Command.RelationshipGroupCommand> relGroupCommands = new ArrayList<>();
    private ArrayList<Command.RelationshipTypeTokenCommand> relationshipTypeTokenCommands;
    private ArrayList<Command.LabelTokenCommand> labelTokenCommands;
    private ArrayList<Command.PropertyKeyTokenCommand> propertyKeyTokenCommands;
    private Command.NeoStoreCommand neoStoreCommand;

    private boolean committed = false;
    private boolean prepared = false;

    private final long lastCommittedTxWhenTransactionStarted;
    private final TransactionState state;
    private final CacheAccessBackDoor cacheAccess;
    private final IndexingService indexes;
    private final NeoStore neoStore;
    private final LabelScanStore labelScanStore;
    private final IntegrityValidator integrityValidator;
    private final KernelTransactionImplementation kernelTransaction;
    private final LockService locks;
    private Collection<NodeRecord> upgradedDenseNodes;

    /**
     * @param lastCommittedTxWhenTransactionStarted is the highest committed transaction id when this transaction
     *                                              begun. No operations in this transaction are allowed to have
     *                                              taken place before that transaction id. This is used by
     *                                              constraint validation - if a constraint was not online when this
     *                                              transaction begun, it will be verified during prepare. If you are
     *                                              writing code against this API and are unsure about what to set
     *                                              this value to, 0 is a safe choice. That will ensure all
     *                                              constraints are checked.
     * @param kernelTransaction is the vanilla sauce to the WriteTransaction apple pie.
     */
    NeoStoreTransaction( long lastCommittedTxWhenTransactionStarted, XaLogicalLog log,
                      TransactionState state, NeoStore neoStore, CacheAccessBackDoor cacheAccess,
                      IndexingService indexingService, LabelScanStore labelScanStore,
                      IntegrityValidator integrityValidator, KernelTransactionImplementation kernelTransaction,
                      LockService locks )
    {
        super( log, state );
        this.lastCommittedTxWhenTransactionStarted = lastCommittedTxWhenTransactionStarted;
        this.neoStore = neoStore;
        this.state = state;
        this.cacheAccess = cacheAccess;
        this.indexes = indexingService;
        this.labelScanStore = labelScanStore;
        this.integrityValidator = integrityValidator;
        this.kernelTransaction = kernelTransaction;
        this.locks = locks;
    }

    /**
     * This is a smell, a result of the kernel refactorings. Right now, both NeoStoreTransaction and KernelTransaction
     * are "publicly" consumable, and one owns the other. In the future, they should be merged such that
     * KernelTransaction rules supreme, and has internal components to manage the responsibilities currently handled by
     * WriteTransaction and ReadTransaction.
     */
    public KernelTransactionImplementation kernelTransaction()
    {
        return kernelTransaction;
    }

    @Override
    public boolean isReadOnly()
    {
        if ( isRecovered() )
        {
            return nodeCommands.size() == 0 && propCommands.size() == 0 &&
                   relCommands.size() == 0 && schemaRuleCommands.size() == 0 && relationshipTypeTokenCommands == null &&
                   labelTokenCommands == null && propertyKeyTokenCommands == null && kernelTransaction.isReadOnly();
        }
        return nodeRecords.changeSize() == 0 && relRecords.changeSize() == 0 && schemaRuleChanges.changeSize() == 0 &&
               propertyRecords.changeSize() == 0 && relationshipTypeTokenRecords == null && labelTokenRecords == null &&
               propertyKeyTokenRecords == null && kernelTransaction.isReadOnly();
    }

    // Make this accessible in this package
    @Override
    protected void setRecovered()
    {
        super.setRecovered();
    }

    @Override
    public void doAddCommand( XaCommand command )
    {
        // override
    }

    @Override
    protected void doPrepare() throws XAException
    {
        if ( committed )
        {
            throw new XAException( "Cannot prepare committed transaction["
                    + getIdentifier() + "]" );
        }
        if ( prepared )
        {
            throw new XAException( "Cannot prepare prepared transaction["
                    + getIdentifier() + "]" );
        }

        prepared = true;

        int noOfCommands = nodeRecords.changeSize() +
                           relRecords.changeSize() +
                           propertyRecords.changeSize() +
                           schemaRuleChanges.changeSize() +
                           (propertyKeyTokenRecords != null ? propertyKeyTokenRecords.size() : 0) +
                           (relationshipTypeTokenRecords != null ? relationshipTypeTokenRecords.size() : 0) +
                           (labelTokenRecords != null ? labelTokenRecords.size() : 0) +
                           relGroupRecords.changeSize();
        List<Command> commands = new ArrayList<>( noOfCommands );
        if ( relationshipTypeTokenRecords != null )
        {
            relationshipTypeTokenCommands = new ArrayList<>();
            for ( RelationshipTypeTokenRecord record : relationshipTypeTokenRecords.values() )
            {
                Command.RelationshipTypeTokenCommand command =
                        new Command.RelationshipTypeTokenCommand(
                                neoStore.getRelationshipTypeStore(), record );
                relationshipTypeTokenCommands.add( command );
                commands.add( command );
            }
        }
        if ( labelTokenRecords != null )
        {
            labelTokenCommands = new ArrayList<>();
            for ( LabelTokenRecord record : labelTokenRecords.values() )
            {
                Command.LabelTokenCommand command =
                        new Command.LabelTokenCommand(
                                neoStore.getLabelTokenStore(), record );
                labelTokenCommands.add( command );
                commands.add( command );
            }
        }
        for ( RecordChange<Long, NodeRecord, Void> change : nodeRecords.changes() )
        {
            NodeRecord record = change.forReadingLinkage();
            integrityValidator.validateNodeRecord( record );
            Command.NodeCommand command = new Command.NodeCommand(
                    neoStore.getNodeStore(), change.getBefore(), record );
            nodeCommands.put( record.getId(), command );
            commands.add( command );
        }
        if ( upgradedDenseNodes != null )
        {
            for ( NodeRecord node : upgradedDenseNodes )
            {
                removeNodeFromCache( node.getId() );
            }
        }
        for ( RecordChange<Long, RelationshipRecord, Void> record : relRecords.changes() )
        {
            Command.RelationshipCommand command = new Command.RelationshipCommand(
                    neoStore.getRelationshipStore(), record.forReadingLinkage() );
            relCommands.add( command );
            commands.add( command );
        }
        if ( neoStoreRecord != null )
        {
            for ( RecordChange<Long, NeoStoreRecord, Void> change : neoStoreRecord.changes() )
            {
                neoStoreCommand = new Command.NeoStoreCommand( neoStore, change.forReadingData() );
                addCommand( neoStoreCommand );
            }
        }
        if ( propertyKeyTokenRecords != null )
        {
            propertyKeyTokenCommands = new ArrayList<>();
            for ( PropertyKeyTokenRecord record : propertyKeyTokenRecords.values() )
            {
                Command.PropertyKeyTokenCommand command =
                        new Command.PropertyKeyTokenCommand(
                                neoStore.getPropertyStore().getPropertyKeyTokenStore(), record );
                propertyKeyTokenCommands.add( command );
                commands.add( command );
            }
        }
        for ( RecordChange<Long, PropertyRecord, PrimitiveRecord> change : propertyRecords.changes() )
        {
            Command.PropertyCommand command = new Command.PropertyCommand(
                    neoStore.getPropertyStore(), change.getBefore(), change.forReadingLinkage() );
            propCommands.add( command );
            commands.add( command );
        }
        for ( RecordChange<Long, Collection<DynamicRecord>, SchemaRule> change : schemaRuleChanges.changes() )
        {
            integrityValidator.validateSchemaRule( change.getAdditionalData() );
            Command.SchemaRuleCommand command = new Command.SchemaRuleCommand(
                    neoStore,
                    neoStore.getSchemaStore(),
                    indexes,
                    change.getBefore(),
                    change.forChangingData(),
                    change.getAdditionalData(),
                    -1 );
            schemaRuleCommands.add( command );
            commands.add( command );
        }
        for ( RecordChange<Long, RelationshipGroupRecord, Integer> change : relGroupRecords.changes() )
        {
            Command.RelationshipGroupCommand command =
                    new Command.RelationshipGroupCommand( neoStore.getRelationshipGroupStore(),
                            change.forReadingData() );
            relGroupCommands.add( command );
            commands.add( command );
        }
        assert commands.size() == noOfCommands : "Expected " + noOfCommands
                                                 + " final commands, got "
                                                 + commands.size() + " instead";
        intercept( commands );

        for ( Command command : commands )
        {
            addCommand( command );
        }

        integrityValidator.validateTransactionStartKnowledge( lastCommittedTxWhenTransactionStarted );
    }

    protected void intercept( List<Command> commands )
    {
        // default no op
    }

    @Override
    protected void injectCommand( XaCommand xaCommand )
    {
        if ( xaCommand instanceof Command.NodeCommand )
        {
            NodeCommand nodeCommand = (Command.NodeCommand) xaCommand;
            nodeCommands.put( nodeCommand.getKey(), nodeCommand );
        }
        else if ( xaCommand instanceof Command.RelationshipCommand )
        {
            relCommands.add( (Command.RelationshipCommand) xaCommand );
        }
        else if ( xaCommand instanceof Command.PropertyCommand )
        {
            propCommands.add( (Command.PropertyCommand) xaCommand );
        }
        else if ( xaCommand instanceof Command.PropertyKeyTokenCommand )
        {
            if ( propertyKeyTokenCommands == null )
            {
                propertyKeyTokenCommands = new ArrayList<>();
            }
            propertyKeyTokenCommands.add( (Command.PropertyKeyTokenCommand) xaCommand );
        }
        else if ( xaCommand instanceof Command.RelationshipTypeTokenCommand )
        {
            if ( relationshipTypeTokenCommands == null )
            {
                relationshipTypeTokenCommands = new ArrayList<>();
            }
            relationshipTypeTokenCommands.add( (Command.RelationshipTypeTokenCommand) xaCommand );
        }
        else if ( xaCommand instanceof Command.LabelTokenCommand )
        {
            if ( labelTokenCommands == null )
            {
                labelTokenCommands = new ArrayList<>();
            }
            labelTokenCommands.add( (Command.LabelTokenCommand) xaCommand );
        }
        else if ( xaCommand instanceof Command.NeoStoreCommand )
        {
            assert neoStoreCommand == null;
            neoStoreCommand = (Command.NeoStoreCommand) xaCommand;
        }
        else if ( xaCommand instanceof Command.SchemaRuleCommand )
        {
            schemaRuleCommands.add( (Command.SchemaRuleCommand) xaCommand );
        }
        else if ( xaCommand instanceof Command.RelationshipGroupCommand )
        {
            relGroupCommands.add( (RelationshipGroupCommand) xaCommand );
        }
        else
        {
            throw new IllegalArgumentException( "Unknown command " + xaCommand );
        }
    }

    @Override
    public void doRollback() throws XAException
    {
        if ( committed )
        {
            throw new XAException( "Cannot rollback partialy commited "
                                   + "transaction[" + getIdentifier() + "]. Recover and "
                                   + "commit" );
        }
        try
        {
            boolean freeIds = neoStore.freeIdsDuringRollback();
            if ( relationshipTypeTokenRecords != null )
            {
                for ( RelationshipTypeTokenRecord record : relationshipTypeTokenRecords.values() )
                {
                    if ( record.isCreated() )
                    {
                        if ( freeIds )
                        {
                            getRelationshipTypeStore().freeId( record.getId() );
                        }
                        for ( DynamicRecord dynamicRecord : record.getNameRecords() )
                        {
                            if ( dynamicRecord.isCreated() )
                            {
                                getRelationshipTypeStore().freeId(
                                        (int) dynamicRecord.getId() );
                            }
                        }
                    }
                    removeRelationshipTypeFromCache( record.getId() );
                }
            }
            for ( RecordChange<Long, NodeRecord, Void> change : nodeRecords.changes() )
            {
                NodeRecord record = change.forReadingLinkage();
                if ( freeIds && record.isCreated() )
                {
                    getNodeStore().freeId( record.getId() );
                }
                removeNodeFromCache( record.getId() );
            }
            for ( RecordChange<Long, RelationshipRecord, Void> change : relRecords.changes() )
            {
                long id = change.getKey();
                RelationshipRecord record = change.forReadingLinkage();
                if ( freeIds && change.isCreated() )
                {
                    getRelationshipStore().freeId( id );
                }
                removeRelationshipFromCache( id );
                patchDeletedRelationshipNodes( id, record.getFirstNode(), record.getFirstNextRel(),
                                               record.getSecondNode(), record.getSecondNextRel() );
            }
            if ( neoStoreRecord != null )
            {
                removeGraphPropertiesFromCache();
            }
            if ( propertyKeyTokenRecords != null )
            {
                for ( PropertyKeyTokenRecord record : propertyKeyTokenRecords.values() )
                {
                    if ( record.isCreated() )
                    {
                        if ( freeIds )
                        {
                            getPropertyStore().getPropertyKeyTokenStore().freeId( record.getId() );
                        }
                        for ( DynamicRecord dynamicRecord : record.getNameRecords() )
                        {
                            if ( dynamicRecord.isCreated() )
                            {
                                getPropertyStore().getPropertyKeyTokenStore().freeId(
                                        (int) dynamicRecord.getId() );
                            }
                        }
                    }
                }
            }
            for ( RecordChange<Long, PropertyRecord, PrimitiveRecord> change : propertyRecords.changes() )
            {
                PropertyRecord record = change.forReadingLinkage();
                if ( record.getNodeId() != -1 )
                {
                    removeNodeFromCache( record.getNodeId() );
                }
                else if ( record.getRelId() != -1 )
                {
                    removeRelationshipFromCache( record.getRelId() );
                }
                if ( record.isCreated() )
                {
                    if ( freeIds )
                    {
                        getPropertyStore().freeId( record.getId() );
                    }
                    for ( PropertyBlock block : record.getPropertyBlocks() )
                    {
                        for ( DynamicRecord dynamicRecord : block.getValueRecords() )
                        {
                            if ( dynamicRecord.isCreated() )
                            {
                                if ( dynamicRecord.getType() == PropertyType.STRING.intValue() )
                                {
                                    getPropertyStore().freeStringBlockId(
                                            dynamicRecord.getId() );
                                }
                                else if ( dynamicRecord.getType() == PropertyType.ARRAY.intValue() )
                                {
                                    getPropertyStore().freeArrayBlockId(
                                            dynamicRecord.getId() );
                                }
                                else
                                {
                                    throw new InvalidRecordException(
                                            "Unknown type on " + dynamicRecord );
                                }
                            }
                        }
                    }
                }
            }
            for ( RecordChange<Long, Collection<DynamicRecord>, SchemaRule> records : schemaRuleChanges.changes() )
            {
                long id = -1;
                for ( DynamicRecord record : records.forChangingData() )
                {
                    if ( id == -1 )
                    {
                        id = record.getId();
                    }
                    if ( freeIds && record.isCreated() )
                    {
                        getSchemaStore().freeId( record.getId() );
                    }
                }
            }
            for ( RecordChange<Long, RelationshipGroupRecord, Integer> change : relGroupRecords.changes() )
            {
                RelationshipGroupRecord record = change.forReadingData();
                if ( freeIds && record.isCreated() )
                {
                    getRelationshipGroupStore().freeId( record.getId() );
                }
            }
        }
        finally
        {
            clear();
        }
    }

    private void removeRelationshipTypeFromCache( int id )
    {
        cacheAccess.removeRelationshipTypeFromCache( id );
    }

    private void patchDeletedRelationshipNodes( long id, long firstNodeId, long firstNodeNextRelId, long secondNodeId,
                                                long secondNextRelId )
    {
        cacheAccess.patchDeletedRelationshipNodes( id, firstNodeId, firstNodeNextRelId, secondNodeId, secondNextRelId );
    }

    private void removeRelationshipFromCache( long id )
    {
        cacheAccess.removeRelationshipFromCache( id );
    }

    private void removeNodeFromCache( long id )
    {
        cacheAccess.removeNodeFromCache( id );
    }

    private void removeGraphPropertiesFromCache()
    {
        cacheAccess.removeGraphPropertiesFromCache();
    }

    private void addRelationshipType( int id )
    {
        setRecovered();
        Token type = isRecovered() ?
                     neoStore.getRelationshipTypeStore().getToken( id, true ) :
                     neoStore.getRelationshipTypeStore().getToken( id );
        cacheAccess.addRelationshipTypeToken( type );
    }

    private void addLabel( int id )
    {
        Token labelId = isRecovered() ?
                        neoStore.getLabelTokenStore().getToken( id, true ) :
                        neoStore.getLabelTokenStore().getToken( id );
        cacheAccess.addLabelToken( labelId );
    }

    private void addPropertyKey( int id )
    {
        Token index = isRecovered() ?
                      neoStore.getPropertyStore().getPropertyKeyTokenStore().getToken( id, true ) :
                      neoStore.getPropertyStore().getPropertyKeyTokenStore().getToken( id );
        cacheAccess.addPropertyKeyToken( index );
    }

    @Override
    public void doCommit() throws XAException
    {
        if ( !isRecovered() && !prepared )
        {
            throw new XAException( "Cannot commit non prepared transaction[" + getIdentifier() + "]" );
        }
        if ( isRecovered() )
        {
            boolean wasInRecovery = neoStore.isInRecoveryMode();
            neoStore.setRecoveredStatus( true );
            try
            {
                applyCommit( true );
                return;
            }
            finally
            {
                neoStore.setRecoveredStatus( wasInRecovery );
            }
        }
        if ( getCommitTxId() != neoStore.getLastCommittedTx() + 1 )
        {
            throw new RuntimeException( "Tx id: " + getCommitTxId() +
                                        " not next transaction (" + neoStore.getLastCommittedTx() + ")" );
        }

        applyCommit( false );
    }

    private void applyCommit( boolean isRecovered )
    {
        try ( LockGroup lockGroup = new LockGroup() )
        {
            committed = true;
            CommandSorter sorter = new CommandSorter();
            // reltypes
            if ( relationshipTypeTokenCommands != null )
            {
                java.util.Collections.sort( relationshipTypeTokenCommands, sorter );
                for ( Command.RelationshipTypeTokenCommand command : relationshipTypeTokenCommands )
                {
                    command.execute();
                    if ( isRecovered )
                    {
                        addRelationshipType( (int) command.getKey() );
                    }
                }
            }
            // label keys
            if ( labelTokenCommands != null )
            {
                java.util.Collections.sort( labelTokenCommands, sorter );
                for ( Command.LabelTokenCommand command : labelTokenCommands )
                {
                    command.execute();
                    if ( isRecovered )
                    {
                        addLabel( (int) command.getKey() );
                    }
                }
            }
            // property keys
            if ( propertyKeyTokenCommands != null )
            {
                java.util.Collections.sort( propertyKeyTokenCommands, sorter );
                for ( Command.PropertyKeyTokenCommand command : propertyKeyTokenCommands )
                {
                    command.execute();
                    if ( isRecovered )
                    {
                        addPropertyKey( (int) command.getKey() );
                    }
                }
            }

            // primitives
            java.util.Collections.sort( relCommands, sorter );
            java.util.Collections.sort( propCommands, sorter );
            executeCreated( lockGroup, isRecovered, propCommands, relCommands, nodeCommands.values(), relGroupCommands );
            executeModified( lockGroup, isRecovered, propCommands, relCommands, nodeCommands.values(), relGroupCommands );
            executeDeleted( lockGroup, propCommands, relCommands, nodeCommands.values(), relGroupCommands );

            // property change set for index updates
            Collection<NodeLabelUpdate> labelUpdates = gatherLabelUpdatesSortedByNodeId();
            if ( !labelUpdates.isEmpty() )
            {
                updateLabelScanStore( labelUpdates );
                cacheAccess.applyLabelUpdates( labelUpdates );
            }

            if ( !nodeCommands.isEmpty() || !propCommands.isEmpty() )
            {
                indexes.updateIndexes( new LazyIndexUpdates(
                        getNodeStore(), getPropertyStore(),
                        new ArrayList<>( propCommands ), new HashMap<>( nodeCommands ) ) );
            }

            // schema rules. Execute these after generating the property updates so. If executed
            // before and we've got a transaction that sets properties/labels as well as creating an index
            // we might end up with this corner-case:
            // 1) index rule created and index population job started
            // 2) index population job processes some nodes, but doesn't complete
            // 3) we gather up property updates and send those to the indexes. The newly created population
            //    job might get those as updates
            // 4) the population job will apply those updates as added properties, and might end up with duplicate
            //    entries for the same property
            for ( SchemaRuleCommand command : schemaRuleCommands )
            {
                command.setTxId( getCommitTxId() );
                command.execute();
                switch ( command.getMode() )
                {
                case DELETE:
                    cacheAccess.removeSchemaRuleFromCache( command.getKey() );
                    break;
                default:
                    cacheAccess.addSchemaRule( command.getSchemaRule() );
                }
            }

            if ( neoStoreCommand != null )
            {
                neoStoreCommand.execute();
                if ( isRecovered )
                {
                    removeGraphPropertiesFromCache();
                }
            }
            if ( !isRecovered )
            {
                updateFirstRelationships();
                state.commitCows(); // updates the cached primitives
            }
            neoStore.setLastCommittedTx( getCommitTxId() );
            if ( isRecovered )
            {
                neoStore.updateIdGenerators();
            }
        }
        finally
        {
            clear();
        }
    }

    private Collection<NodeLabelUpdate> gatherLabelUpdatesSortedByNodeId()
    {
        List<NodeLabelUpdate> labelUpdates = new ArrayList<>();
        for ( NodeCommand nodeCommand : nodeCommands.values() )
        {
            NodeLabels labelFieldBefore = parseLabelsField( nodeCommand.getBefore() );
            NodeLabels labelFieldAfter = parseLabelsField( nodeCommand.getAfter() );
            if ( labelFieldBefore.isInlined() && labelFieldAfter.isInlined()
                 && nodeCommand.getBefore().getLabelField() == nodeCommand.getAfter().getLabelField() )
            {
                continue;
            }
            long[] labelsBefore = labelFieldBefore.getIfLoaded();
            long[] labelsAfter = labelFieldAfter.getIfLoaded();
            if ( labelsBefore == null || labelsAfter == null )
            {
                continue;
            }
            labelUpdates.add( NodeLabelUpdate.labelChanges( nodeCommand.getKey(), labelsBefore, labelsAfter ) );
        }

        Collections.sort(labelUpdates, new NodeLabelUpdateNodeIdComparator());

        return labelUpdates;
    }

    private void updateLabelScanStore( Iterable<NodeLabelUpdate> labelUpdates )
    {
        try ( LabelScanWriter writer = labelScanStore.newWriter() )
        {
            for ( NodeLabelUpdate update : labelUpdates )
            {
                writer.write( update );
            }
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException( e );
        }
    }

    static class LabelChangeSummary
    {
        private static final long[] NO_LABELS = new long[0];

        private final long[] addedLabels;
        private final long[] removedLabels;

        LabelChangeSummary( long[] labelsBefore, long[] labelsAfter )
        {
            // Ids are sorted in the store
            long[] addedLabels = new long[labelsAfter.length];
            long[] removedLabels = new long[labelsBefore.length];
            int addedLabelsCursor = 0, removedLabelsCursor = 0;
            for ( long labelAfter : labelsAfter )
            {
                if ( binarySearch( labelsBefore, labelAfter ) < 0 )
                {
                    addedLabels[addedLabelsCursor++] = labelAfter;
                }
            }
            for ( long labelBefore : labelsBefore )
            {
                if ( binarySearch( labelsAfter, labelBefore ) < 0 )
                {
                    removedLabels[removedLabelsCursor++] = labelBefore;
                }
            }

            // For each property on the node, produce one update for added labels and one for removed labels.
            this.addedLabels = shrink( addedLabels, addedLabelsCursor );
            this.removedLabels = shrink( removedLabels, removedLabelsCursor );
        }

        private long[] shrink( long[] array, int toLength )
        {
            if ( toLength == 0 )
            {
                return NO_LABELS;
            }
            return array.length == toLength ? array : copyOf( array, toLength );
        }

        public boolean hasAddedLabels()
        {
            return addedLabels.length > 0;
        }

        public boolean hasRemovedLabels()
        {
            return removedLabels.length > 0;
        }

        public long[] getAddedLabels()
        {
            return addedLabels;
        }

        public long[] getRemovedLabels()
        {
            return removedLabels;
        }
    }

    private void updateFirstRelationships()
    {
        for ( RecordChange<Long, NodeRecord, Void> change : nodeRecords.changes() )
        {
            NodeRecord record = change.forReadingLinkage();
            state.setFirstIds( record.getId(), record.getNextRel(), record.getNextProp() );
        }
    }

    @SafeVarargs
    private final void executeCreated( LockGroup lockGroup, boolean removeFromCache,
                                       Collection<? extends Command>... commands )
    {
        for ( Collection<? extends Command> c : commands )
        {
            for ( Command command : c )
            {
                if ( command.getMode() == CREATE )
                {
                    lockEntity( lockGroup, command );
                    command.execute();
                    if ( removeFromCache )
                    {
                        command.removeFromCache( cacheAccess );
                    }
                }
            }
        }
    }

    @SafeVarargs
    private final void executeModified( LockGroup lockGroup, boolean removeFromCache,
                                        Collection<? extends Command>... commands )
    {
        for ( Collection<? extends Command> c : commands )
        {
            for ( Command command : c )
            {
                if ( command.getMode() == UPDATE )
                {
                    lockEntity( lockGroup, command );
                    command.execute();
                    if ( removeFromCache )
                    {
                        command.removeFromCache( cacheAccess );
                    }
                }
            }
        }
    }

    @SafeVarargs
    private final void executeDeleted( LockGroup lockGroup, Collection<? extends Command>... commands )
    {
        for ( Collection<? extends Command> c : commands )
        {
            for ( Command command : c )
            {
                if ( command.getMode() == DELETE )
                {
                /*
                 * We always update the disk image and then always invalidate the cache. In the case of relationships
                 * this is expected to also patch the relChainPosition in the start and end NodeImpls (if they actually
                 * are in cache).
                 */
                    lockEntity( lockGroup, command );
                    command.execute();
                    command.removeFromCache( cacheAccess );
                }
            }
        }
    }

    private void lockEntity( LockGroup lockGroup, Command command )
    {
        if ( command instanceof NodeCommand )
        {
            lockGroup.add( locks.acquireNodeLock( command.getKey(), LockService.LockType.WRITE_LOCK ) );
        }
        if ( command instanceof Command.PropertyCommand )
        {
            long nodeId = ((Command.PropertyCommand) command).getNodeId();
            if ( nodeId != -1 )
            {
                lockGroup.add( locks.acquireNodeLock( nodeId, LockService.LockType.WRITE_LOCK ) );
            }
        }
    }

    private void clear()
    {
        nodeRecords.clear();
        propertyRecords.clear();
        relRecords.clear();
        schemaRuleChanges.clear();
        relationshipTypeTokenRecords = null;
        propertyKeyTokenRecords = null;
        relGroupRecords.clear();
        relGroupCache.clear();
        neoStoreRecord = null;

        nodeCommands.clear();
        propCommands.clear();
        propertyKeyTokenCommands = null;
        relCommands.clear();
        schemaRuleCommands.clear();
        relationshipTypeTokenCommands = null;
        labelTokenCommands = null;
        relGroupCommands.clear();
        neoStoreCommand = null;
    }

    private RelationshipTypeTokenStore getRelationshipTypeStore()
    {
        return neoStore.getRelationshipTypeStore();
    }

    private LabelTokenStore getLabelTokenStore()
    {
        return neoStore.getLabelTokenStore();
    }

    private int getRelGrabSize()
    {
        return neoStore.getRelationshipGrabSize();
    }

    private NodeStore getNodeStore()
    {
        return neoStore.getNodeStore();
    }

    private SchemaStore getSchemaStore()
    {
        return neoStore.getSchemaStore();
    }

    private RelationshipStore getRelationshipStore()
    {
        return neoStore.getRelationshipStore();
    }

    private RelationshipGroupStore getRelationshipGroupStore()
    {
        return neoStore.getRelationshipGroupStore();
    }
    
    private PropertyStore getPropertyStore()
    {
        return neoStore.getPropertyStore();
    }

    /**
     * Tries to load the light node with the given id, returns true on success.
     *
     * @param nodeId The id of the node to load.
     * @return True iff the node record can be found.
     */
    public NodeRecord nodeLoadLight( long nodeId )
    {
        try
        {
            return nodeRecords.getOrLoad( nodeId, null ).forReadingLinkage();
        }
        catch ( InvalidRecordException e )
        {
            return null;
        }
    }

    /**
     * Tries to load the light relationship with the given id, returns the
     * record on success.
     *
     * @param id The id of the relationship to load.
     * @return The light RelationshipRecord if it was found, null otherwise.
     */
    public RelationshipRecord relLoadLight( long id )
    {
        try
        {
            return relRecords.getOrLoad( id, null ).forReadingLinkage();
        }
        catch ( InvalidRecordException e )
        {
            return null;
        }
    }

    /**
     * Deletes a node by its id, returning its properties which are now removed.
     *
     * @param nodeId The id of the node to delete.
     * @return The properties of the node that were removed during the delete.
     */
    public ArrayMap<Integer, DefinedProperty> nodeDelete( long nodeId )
    {
        NodeRecord nodeRecord = nodeRecords.getOrLoad( nodeId, null ).forChangingData();
        if ( !nodeRecord.inUse() )
        {
            throw new IllegalStateException( "Unable to delete Node[" + nodeId +
                                             "] since it has already been deleted." );
        }
        nodeRecord.setInUse( false );
        nodeRecord.setLabelField( 0, Collections.<DynamicRecord>emptyList() );
        return getAndDeletePropertyChain( nodeRecord );
    }

    /**
     * Deletes a relationship by its id, returning its properties which are now
     * removed. It is assumed that the nodes it connects have already been
     * deleted in this
     * transaction.
     *
     * @param id The id of the relationship to delete.
     * @return The properties of the relationship that were removed during the
     *         delete.
     */
    public ArrayMap<Integer, DefinedProperty> relDelete( long id )
    {
        RelationshipRecord record = relRecords.getOrLoad( id, null ).forChangingLinkage();
        if ( !record.inUse() )
        {
            throw new IllegalStateException( "Unable to delete relationship[" +
                                             id + "] since it is already deleted." );
        }
        ArrayMap<Integer, DefinedProperty> propertyMap = getAndDeletePropertyChain( record );
        disconnectRelationship( record );
        updateNodesForDeletedRelationship( record );
        record.setInUse( false );
        return propertyMap;
    }

    private ArrayMap<Integer, DefinedProperty> getAndDeletePropertyChain( PrimitiveRecord primitive )
    {
        ArrayMap<Integer, DefinedProperty> result = new ArrayMap<>( (byte) 9, false, true );
        long nextProp = primitive.getNextProp();
        while ( nextProp != Record.NO_NEXT_PROPERTY.intValue() )
        {
            RecordChange<Long, PropertyRecord, PrimitiveRecord> propertyChange =
                    propertyRecords.getOrLoad( nextProp, primitive );

            // TODO forChanging/forReading piggy-backing
            PropertyRecord propRecord = propertyChange.forChangingData();
            PropertyRecord before = propertyChange.getBefore();
            for ( PropertyBlock block : before.getPropertyBlocks() )
            {
                result.put( block.getKeyIndexId(), block.newPropertyData( getPropertyStore() ) );
            }
            for ( PropertyBlock block : propRecord.getPropertyBlocks() )
            {
                for ( DynamicRecord valueRecord : block.getValueRecords() )
                {
                    assert valueRecord.inUse();
                    valueRecord.setInUse( false );
                    propRecord.addDeletedRecord( valueRecord );
                }
            }
            nextProp = propRecord.getNextProp();
            propRecord.setInUse( false );
            propRecord.setChanged( primitive );
            // We do not remove them individually, but all together here
            propRecord.getPropertyBlocks().clear();
        }
        return result;
    }
    
    private void disconnect( RelationshipRecord rel, RelationshipConnection pointer )
    {
        long otherRelId = pointer.otherSide().get( rel );
        if ( otherRelId == Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            return;
        }

        Relationship lockableRel = new LockableRelationship( otherRelId );
        getWriteLock( lockableRel );
        RelationshipRecord otherRel = relRecords.getOrLoad( otherRelId, null ).forChangingLinkage();
        boolean changed = false;
        long newId = pointer.get( rel );
        boolean newIsFirst = pointer.isFirstInChain( rel );
        if ( otherRel.getFirstNode() == pointer.compareNode( rel ) )
        {
            pointer.start().set( otherRel, newId, newIsFirst );
            changed = true;
        }
        if ( otherRel.getSecondNode() == pointer.compareNode( rel ) )
        {
            pointer.end().set( otherRel, newId, newIsFirst );
            changed = true;
        }
        if ( !changed )
        {
            throw new InvalidRecordException( otherRel + " don't match " + rel );
        }
    }

    private void disconnectRelationship( RelationshipRecord rel )
    {
        disconnect( rel, RelationshipConnection.START_NEXT );
        disconnect( rel, RelationshipConnection.START_PREV );
        disconnect( rel, RelationshipConnection.END_NEXT );
        disconnect( rel, RelationshipConnection.END_PREV );
    }

    private void getWriteLock( Relationship lockableRel )
    {
        state.acquireWriteLock( lockableRel );
    }

    /*
     * List<Iterable<RelationshipRecord>> is a list with three items:
     * 0: outgoing relationships
     * 1: incoming relationships
     * 2: loop relationships
     */
    public Pair<Map<DirectionWrapper, Iterable<RelationshipRecord>>, RelationshipLoadingPosition> getMoreRelationships(
            long nodeId, RelationshipLoadingPosition position, DirectionWrapper direction,
            int[] types )
    {
        return getMoreRelationships( nodeId, position, getRelGrabSize(), direction, types, getRelationshipStore() );
    }
    
    private boolean decrementTotalRelationshipCount( long nodeId, RelationshipRecord rel, long firstRelId )
    {
        if ( firstRelId == Record.NO_PREV_RELATIONSHIP.intValue() )
        {
            return true;
        }
        boolean firstInChain = relIsFirstInChain( nodeId, rel );
        if ( !firstInChain )
        {
            getWriteLock( new LockableRelationship( firstRelId ) );
        }
        RelationshipRecord firstRel = relRecords.getOrLoad( firstRelId, null ).forChangingLinkage();
        if ( nodeId == firstRel.getFirstNode() )
        {
            firstRel.setFirstPrevRel( firstInChain ?
                    relCount( nodeId, rel )-1 : relCount( nodeId, firstRel )-1 );
            firstRel.setFirstInFirstChain( true );
        }
        if ( nodeId == firstRel.getSecondNode() )
        {
            firstRel.setSecondPrevRel( firstInChain ?
                    relCount( nodeId, rel )-1 : relCount( nodeId, firstRel )-1 );
            firstRel.setFirstInSecondChain( true );
        }
        return false;
    }
    
    private boolean relIsFirstInChain( long nodeId, RelationshipRecord rel )
    {
        return (nodeId == rel.getFirstNode() && rel.isFirstInFirstChain()) ||
               (nodeId == rel.getSecondNode() && rel.isFirstInSecondChain());
    }
    
    private int relCount( long nodeId, RelationshipRecord rel )
    {
        return (int) (nodeId == rel.getFirstNode() ? rel.getFirstPrevRel() : rel.getSecondPrevRel());
    }
    
    private DirectionWrapper wrapDirection( RelationshipRecord rel, NodeRecord startNode )
    {
        boolean isOut = rel.getFirstNode() == startNode.getId();
        boolean isIn = rel.getSecondNode() == startNode.getId();
        assert isOut|isIn;
        if ( isOut&isIn )
        {
            return DirectionWrapper.BOTH;
        }
        return isOut ? DirectionWrapper.OUTGOING : DirectionWrapper.INCOMING;
    }
    
    private boolean groupIsEmpty( RelationshipGroupRecord group )
    {
        return group.getFirstOut() == Record.NO_NEXT_RELATIONSHIP.intValue() &&
               group.getFirstIn() == Record.NO_NEXT_RELATIONSHIP.intValue() &&
               group.getFirstLoop() == Record.NO_NEXT_RELATIONSHIP.intValue();
    }
    
    private void deleteGroup( RecordChange<Long,NodeRecord,Void> nodeChange, RelationshipGroupRecord group )
    {
        long previous = group.getPrev();
        long next = group.getNext();
        if ( previous == Record.NO_NEXT_RELATIONSHIP.intValue() )
        {   // This is the first one, just point the node to the next group
            nodeChange.forChangingLinkage().setNextRel( next );
        }
        else
        {   // There are others before it, point the previous to the next group
            RelationshipGroupRecord previousRecord = relGroupRecords.getOrLoad( previous, null ).forChangingLinkage();
            previousRecord.setNext( next );
        }

        if ( next != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {   // There are groups after this one, point that next group to the previous of the group to be deleted
            RelationshipGroupRecord nextRecord = relGroupRecords.getOrLoad( next, null ).forChangingLinkage();
            nextRecord.setPrev( previous );
        }
        group.setInUse( false );
    }

    private void updateNodesForDeletedRelationship( RelationshipRecord rel )
    {
        RecordChange<Long, NodeRecord, Void> startNodeChange = nodeRecords.getOrLoad( rel.getFirstNode(), null );
        RecordChange<Long, NodeRecord, Void> endNodeChange = nodeRecords.getOrLoad( rel.getSecondNode(), null );
        
        NodeRecord startNode = nodeRecords.getOrLoad( rel.getFirstNode(), null ).forReadingLinkage();
        NodeRecord endNode = nodeRecords.getOrLoad( rel.getSecondNode(), null ).forReadingLinkage();
        boolean loop = startNode.getId() == endNode.getId();
        
        if ( !startNode.isDense() )
        {
            if ( rel.isFirstInFirstChain() )
            {
                startNode = startNodeChange.forChangingLinkage();
                startNode.setNextRel( rel.getFirstNextRel() );
            }
            decrementTotalRelationshipCount( startNode.getId(), rel, startNode.getNextRel() );
        }
        else
        {
            RecordChange<Long, RelationshipGroupRecord, Integer> groupChange =
                    getRelationshipGroup( startNode, rel.getType() );
            assert groupChange != null : "Relationship group " + rel.getType() + " should have existed here";
            RelationshipGroupRecord group = groupChange.forReadingData();
            DirectionWrapper dir = wrapDirection( rel, startNode );
            if ( rel.isFirstInFirstChain() )
            {
                group = groupChange.forChangingData();
                dir.setNextRel( group, rel.getFirstNextRel() );
                if ( groupIsEmpty( group ) )
                {
                    deleteGroup( startNodeChange, group );
                }
            }
            decrementTotalRelationshipCount( startNode.getId(), rel, dir.getNextRel( group ) );
        }

        if ( !endNode.isDense() )
        {
            if ( rel.isFirstInSecondChain() )
            {
                endNode = endNodeChange.forChangingLinkage();
                endNode.setNextRel( rel.getSecondNextRel() );
            }
            if ( !loop )
            {
                decrementTotalRelationshipCount( endNode.getId(), rel, endNode.getNextRel() );
            }
        }
        else
        {
            RecordChange<Long, RelationshipGroupRecord, Integer> groupChange =
                    getRelationshipGroup( endNode, rel.getType() );
            DirectionWrapper dir = wrapDirection( rel, endNode );
            assert groupChange != null || loop : "Group has been deleted";
            if ( groupChange != null )
            {
                RelationshipGroupRecord group = groupChange.forReadingData();
                if ( rel.isFirstInSecondChain() )
                {
                    group = groupChange.forChangingData();
                    dir.setNextRel( group, rel.getSecondNextRel() );
                    if ( groupIsEmpty( group ) )
                    {
                        deleteGroup( endNodeChange, group );
                    }
                }
            } // Else this is a loop-rel and the group was deleted when dealing with the start node
            if ( !loop )
            {
                decrementTotalRelationshipCount( endNode.getId(), rel, dir.getNextRel( groupChange.forChangingData() ) );
            }
        }        
    }

    RecordChange<Long, RelationshipGroupRecord, Integer> getRelationshipGroup( NodeRecord node, int type )
    {
        long groupId = node.getNextRel();
        long previousGroupId = Record.NO_NEXT_RELATIONSHIP.intValue();
        Set<Integer> allTypes = new HashSet<>();
        while ( groupId != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            RecordChange<Long, RelationshipGroupRecord, Integer> change = relGroupRecords.getOrLoad( groupId, type );
            RelationshipGroupRecord record = change.forReadingData();
            record.setPrev( previousGroupId ); // not persistent so not a "change"
            allTypes.add( record.getType() );
            if ( record.getType() == type )
            {
                return change;
            }
            previousGroupId = groupId;
            groupId = record.getNext();
        }
        return null;
    }

    private RecordChange<Long, RelationshipGroupRecord, Integer> getOrCreateRelationshipGroup(
            NodeRecord node, int type )
    {
        RecordChange<Long, RelationshipGroupRecord, Integer> change = getRelationshipGroup( node, type );
        if ( change == null )
        {
            assert node.isDense();
            long id = neoStore.getRelationshipGroupStore().nextId();
            long firstGroupId = node.getNextRel();
            change = relGroupRecords.create( id, type );
            RelationshipGroupRecord record = change.forChangingData();
            record.setInUse( true );
            record.setCreated();
            if ( firstGroupId != Record.NO_NEXT_RELATIONSHIP.intValue() )
            {   // There are others, make way for this new group
                RelationshipGroupRecord previousFirstRecord =
                        relGroupRecords.getOrLoad( firstGroupId, type ).forReadingData();
                record.setNext( previousFirstRecord.getId() );
                previousFirstRecord.setPrev( id );
            }
            node.setNextRel( id );
        }
        return change;
    }
    
    /**
     * Removes the given property identified by its index from the relationship
     * with the given id.
     *
     * @param relId The id of the relationship that is to have the property
     *            removed.
     * @param propertyKey The index key of the property.
     */
    public void relRemoveProperty( long relId, int propertyKey )
    {
        RecordChange<Long, RelationshipRecord, Void> rel = relRecords.getOrLoad( relId, null );
        RelationshipRecord relRecord = rel.forReadingLinkage();
        if ( !relRecord.inUse() )
        {
            throw new IllegalStateException( "Property remove on relationship[" +
                                             relId + "] illegal since it has been deleted." );
        }
        assert assertPropertyChain( relRecord );
        removeProperty( relRecord, rel, propertyKey );
    }

    /**
     * Loads the complete property chain for the given relationship and returns
     * it as a map from property index id to property data.
     *
     * @param relId The id of the relationship whose properties to load.
     * @param light If the properties should be loaded light or not.
     * @param receiver receiver of loaded properties.
     */
    public void relLoadProperties( long relId, boolean light, PropertyReceiver receiver )
    {
        RecordChange<Long, RelationshipRecord, Void> rel = relRecords.getIfLoaded( relId );
        if ( rel != null )
        {
            if ( rel.isCreated() )
            {
                return;
            }
            if ( !rel.forReadingLinkage().inUse() && !light )
            {
                throw new IllegalStateException( "Relationship[" + relId + "] has been deleted in this tx" );
            }
        }

        RelationshipRecord relRecord = getRelationshipStore().getRecord( relId );
        if ( !relRecord.inUse() )
        {
            throw new InvalidRecordException( "Relationship[" + relId + "] not in use" );
        }
        loadProperties( getPropertyStore(), relRecord.getNextProp(), receiver );
    }

    /**
     * Loads the complete property chain for the given node and returns it as a
     * map from property index id to property data.
     *
     * @param nodeId The id of the node whose properties to load.
     * @param light If the properties should be loaded light or not.
     * @param receiver receiver of loaded properties.
     */
    public void nodeLoadProperties( long nodeId, boolean light, PropertyReceiver receiver )
    {
        RecordChange<Long, NodeRecord, Void> node = nodeRecords.getIfLoaded( nodeId );
        if ( node != null )
        {
            if ( node.isCreated() )
            {
                return;
            }
            if ( !node.forReadingLinkage().inUse() && !light )
            {
                throw new IllegalStateException( "Node[" + nodeId + "] has been deleted in this tx" );
            }
        }

        NodeRecord nodeRecord = getNodeStore().getRecord( nodeId );
        if ( !nodeRecord.inUse() )
        {
            throw new IllegalStateException( "Node[" + nodeId + "] has been deleted in this tx" );
        }
        loadProperties( getPropertyStore(), nodeRecord.getNextProp(), receiver );
    }

    /**
     * Removes the given property identified by indexKeyId of the node with the
     * given id.
     *
     * @param nodeId The id of the node that is to have the property removed.
     * @param propertyKey The index key of the property.
     */
    public void nodeRemoveProperty( long nodeId, int propertyKey )
    {
        RecordChange<Long, NodeRecord, Void> node = nodeRecords.getOrLoad( nodeId, null );
        NodeRecord nodeRecord = node.forReadingLinkage();
        if ( !nodeRecord.inUse() )
        {
            throw new IllegalStateException( "Property remove on node[" +
                    nodeId + "] illegal since it has been deleted." );
        }
        assert assertPropertyChain( nodeRecord );

        removeProperty( nodeRecord, node, propertyKey );
    }

    private <P extends PrimitiveRecord> void removeProperty( P primitive,
            RecordChange<Long, P, Void> primitiveRecordChange, int propertyKey )
    {
        long propertyId = // propertyData.getId();
                findPropertyRecordContaining( primitive, propertyKey );
        RecordChange<Long, PropertyRecord, PrimitiveRecord> recordChange =
                propertyRecords.getOrLoad( propertyId, primitiveRecordChange.forReadingLinkage() );
        PropertyRecord propRecord = recordChange.forChangingData();
        if ( !propRecord.inUse() )
        {
            throw new IllegalStateException( "Unable to delete property[" +
                    propertyId + "] since it is already deleted." );
        }

        PropertyBlock block = propRecord.removePropertyBlock( propertyKey );
        if ( block == null )
        {
            throw new IllegalStateException( "Property with index["
                                             + propertyKey
                                             + "] is not present in property["
                                             + propertyId + "]" );
        }

        for ( DynamicRecord valueRecord : block.getValueRecords() )
        {
            assert valueRecord.inUse();
            valueRecord.setInUse( false, block.getType().intValue() );
            propRecord.addDeletedRecord( valueRecord );
        }
        if ( propRecord.size() > 0 )
        {
            /*
             * There are remaining blocks in the record. We do not unlink yet.
             */
            propRecord.setChanged( primitiveRecordChange.forReadingLinkage() );
            assert assertPropertyChain( primitiveRecordChange.forReadingLinkage() );
        }
        else
        {
            unlinkPropertyRecord( propRecord, primitiveRecordChange );
        }
    }

    private <P extends PrimitiveRecord> void unlinkPropertyRecord( PropertyRecord propRecord,
                                                                   RecordChange<Long, P, Void> primitiveRecordChange )
    {
        P primitive = primitiveRecordChange.forReadingLinkage();
        assert assertPropertyChain( primitive );
        assert propRecord.size() == 0;
        long prevProp = propRecord.getPrevProp();
        long nextProp = propRecord.getNextProp();
        if ( primitive.getNextProp() == propRecord.getId() )
        {
            assert propRecord.getPrevProp() == Record.NO_PREVIOUS_PROPERTY.intValue() : propRecord
                                                                                        + " for "
                                                                                        + primitive;
            primitiveRecordChange.forChangingLinkage().setNextProp( nextProp );
        }
        if ( prevProp != Record.NO_PREVIOUS_PROPERTY.intValue() )
        {
            PropertyRecord prevPropRecord = propertyRecords.getOrLoad( prevProp, primitive ).forChangingLinkage();
            assert prevPropRecord.inUse() : prevPropRecord + "->" + propRecord
                                            + " for " + primitive;
            prevPropRecord.setNextProp( nextProp );
            prevPropRecord.setChanged( primitive );
        }
        if ( nextProp != Record.NO_NEXT_PROPERTY.intValue() )
        {
            PropertyRecord nextPropRecord = propertyRecords.getOrLoad( nextProp, primitive ).forChangingLinkage();
            assert nextPropRecord.inUse() : propRecord + "->" + nextPropRecord
                                            + " for " + primitive;
            nextPropRecord.setPrevProp( prevProp );
            nextPropRecord.setChanged( primitive );
        }
        propRecord.setInUse( false );
        /*
         *  The following two are not needed - the above line does all the work (PropertyStore
         *  does not write out the prev/next for !inUse records). It is nice to set this
         *  however to check for consistency when assertPropertyChain().
         */
        propRecord.setPrevProp( Record.NO_PREVIOUS_PROPERTY.intValue() );
        propRecord.setNextProp( Record.NO_NEXT_PROPERTY.intValue() );
        propRecord.setChanged( primitive );
        assert assertPropertyChain( primitive );
    }

    /**
     * Changes an existing property's value of the given relationship, with the
     * given index to the passed value
     *
     * @param relId The id of the relationship which holds the property to
     *            change.
     * @param propertyKey The index of the key of the property to change.
     * @param value The new value of the property.
     * @return The changed property, as a PropertyData object.
     */
    public DefinedProperty relChangeProperty( long relId, int propertyKey, Object value )
    {
        RecordChange<Long, RelationshipRecord, Void> rel = relRecords.getOrLoad( relId, null );
        if ( !rel.forReadingLinkage().inUse() )
        {
            throw new IllegalStateException( "Property change on relationship[" +
                                             relId + "] illegal since it has been deleted." );
        }
        return primitiveChangeProperty( rel, propertyKey, value );
    }

    /**
     * Changes an existing property of the given node, with the given index to
     * the passed value
     *
     * @param nodeId The id of the node which holds the property to change.
     * @param propertyKey The index of the key of the property to change.
     * @param value The new value of the property.
     * @return The changed property, as a PropertyData object.
     */
    public DefinedProperty nodeChangeProperty( long nodeId, int propertyKey, Object value )
    {
        RecordChange<Long, NodeRecord, Void> node = nodeRecords.getOrLoad( nodeId, null ); //getNodeRecord( nodeId );
        if ( !node.forReadingLinkage().inUse() )
        {
            throw new IllegalStateException( "Property change on node[" +
                                             nodeId + "] illegal since it has been deleted." );
        }
        return primitiveChangeProperty( node, propertyKey, value );
    }

    /**
     * TODO MP: itroduces performance regression
     * This method was introduced during moving handling of entity properties from NodeImpl/RelationshipImpl
     * to the {@link KernelAPI}. Reason was that the {@link Property} object at the time didn't have a notion
     * of property record id, and didn't want to have it.
     */
    private long findPropertyRecordContaining( PrimitiveRecord primitive, int propertyKey )
    {
        long propertyRecordId = primitive.getNextProp();
        while ( !Record.NO_NEXT_PROPERTY.is( propertyRecordId ) )
        {
            PropertyRecord propertyRecord =
                    propertyRecords.getOrLoad( propertyRecordId, primitive ).forReadingLinkage();
            if ( propertyRecord.getPropertyBlock( propertyKey ) != null )
            {
                return propertyRecordId;
            }
            propertyRecordId = propertyRecord.getNextProp();
        }
        throw new IllegalStateException( "No property record in property chain for " + primitive +
                " contained property with key " + propertyKey );
    }

    private <P extends PrimitiveRecord> DefinedProperty primitiveChangeProperty(
            RecordChange<Long, P, Void> primitiveRecordChange, int propertyKey, Object value )
    {
        P primitive = primitiveRecordChange.forReadingLinkage();
        assert assertPropertyChain( primitive );
        long propertyId = // propertyData.getId();
                findPropertyRecordContaining( primitive, propertyKey );
        PropertyRecord propertyRecord = propertyRecords.getOrLoad( propertyId, primitive ).forChangingData();
        if ( !propertyRecord.inUse() )
        {
            throw new IllegalStateException( "Unable to change property["
                                             + propertyId
                                             + "] since it has been deleted." );
        }
        PropertyBlock block = propertyRecord.getPropertyBlock( propertyKey );
        if ( block == null )
        {
            throw new IllegalStateException( "Property with index["
                                             + propertyKey
                                             + "] is not present in property["
                                             + propertyId + "]" );
        }
        propertyRecord.setChanged( primitive );
        for ( DynamicRecord record : block.getValueRecords() )
        {
            assert record.inUse();
            record.setInUse( false, block.getType().intValue() );
            propertyRecord.addDeletedRecord( record );
        }
        getPropertyStore().encodeValue( block, propertyKey, value );
        if ( propertyRecord.size() > PropertyType.getPayloadSize() )
        {
            propertyRecord.removePropertyBlock( propertyKey );
            /*
             * The record should never, ever be above max size. Less obviously, it should
             * never remain empty. If removing a property because it won't fit when changing
             * it leaves the record empty it means that this block was the last one which
             * means that it doesn't fit in an empty record. Where i come from, we call this
             * weird.
             *
             assert propertyRecord.size() <= PropertyType.getPayloadSize() : propertyRecord;
             assert propertyRecord.size() > 0 : propertyRecord;
             */
            addPropertyBlockToPrimitive( block, primitiveRecordChange );
        }
        assert assertPropertyChain( primitive );
        return Property.property( propertyKey, value );
    }

    private <P extends PrimitiveRecord> DefinedProperty addPropertyToPrimitive(
            RecordChange<Long, P, Void> node, int propertyKey, Object value )
    {
        P record = node.forReadingLinkage();
        assert assertPropertyChain( record );
        PropertyBlock block = new PropertyBlock();
        getPropertyStore().encodeValue( block, propertyKey, value );
        addPropertyBlockToPrimitive( block, node );
        assert assertPropertyChain( record );
        return Property.property( propertyKey, value );
    }

    /**
     * Adds a property to the given relationship, with the given index and
     * value.
     *
     * @param relId The id of the relationship to which to add the property.
     * @param propertyKey The index of the key of the property to add.
     * @param value The value of the property.
     * @return The added property, as a PropertyData object.
     */
    public DefinedProperty relAddProperty( long relId, int propertyKey, Object value )
    {
        RecordChange<Long, RelationshipRecord, Void> rel = relRecords.getOrLoad( relId, null );
        RelationshipRecord relRecord = rel.forReadingLinkage();
        if ( !relRecord.inUse() )
        {
            throw new IllegalStateException( "Property add on relationship[" +
                                             relId + "] illegal since it has been deleted." );
        }
        return addPropertyToPrimitive( rel, propertyKey, value );
    }
    
    /**
     * Adds a property to the given node, with the given index and value.
     *
     * @param nodeId The id of the node to which to add the property.
     * @param propertyKey The index of the key of the property to add.
     * @param value The value of the property.
     * @return The added property, as a PropertyData object.
     */
    public DefinedProperty nodeAddProperty( long nodeId, int propertyKey, Object value )
    {
        RecordChange<Long, NodeRecord, Void> node = nodeRecords.getOrLoad( nodeId, null );
        NodeRecord nodeRecord = node.forReadingLinkage();
        if ( !nodeRecord.inUse() )
        {
            throw new IllegalStateException( "Property add on node[" +
                                             nodeId + "] illegal since it has been deleted." );
        }
        return addPropertyToPrimitive( node, propertyKey, value );
    }

    private <P extends PrimitiveRecord> void addPropertyBlockToPrimitive(
            PropertyBlock block, RecordChange<Long, P, Void> primitiveRecordChange )
    {
        P primitive = primitiveRecordChange.forReadingLinkage();
        assert assertPropertyChain( primitive );
        int newBlockSizeInBytes = block.getSize();
        /*
         * Here we could either iterate over the whole chain or just go for the first record
         * which is the most likely to be the less full one. Currently we opt for the second
         * to perform better.
         */
        PropertyRecord host = null;
        long firstProp = primitive.getNextProp();
        if ( firstProp != Record.NO_NEXT_PROPERTY.intValue() )
        {
            // We do not store in map - might not have enough space
            RecordChange<Long, PropertyRecord, PrimitiveRecord> change = propertyRecords
                    .getOrLoad( firstProp, primitive );
            PropertyRecord propRecord = change.forReadingLinkage();
            assert propRecord.getPrevProp() == Record.NO_PREVIOUS_PROPERTY.intValue() : propRecord
                                                                                        + " for "
                                                                                        + primitive;
            assert propRecord.inUse() : propRecord;
            int propSize = propRecord.size();
            assert propSize > 0 : propRecord;
            if ( propSize + newBlockSizeInBytes <= PropertyType.getPayloadSize() )
            {
                propRecord = change.forChangingData();
                host = propRecord;
                host.addPropertyBlock( block );
                host.setChanged( primitive );
            }
        }
        if ( host == null )
        {
            // First record in chain didn't fit, make new one
            host = propertyRecords.create( getPropertyStore().nextId(), primitive ).forChangingData();
            if ( primitive.getNextProp() != Record.NO_NEXT_PROPERTY.intValue() )
            {
                PropertyRecord prevProp = propertyRecords.getOrLoad( primitive.getNextProp(), primitive )
                                                         .forChangingLinkage();
                assert prevProp.getPrevProp() == Record.NO_PREVIOUS_PROPERTY.intValue();
                prevProp.setPrevProp( host.getId() );
                host.setNextProp( prevProp.getId() );
                prevProp.setChanged( primitive );
            }
            primitiveRecordChange.forChangingLinkage().setNextProp( host.getId() );
            host.addPropertyBlock( block );
            host.setInUse( true );
        }
        // Ok, here host does for the job. Use it
        assert assertPropertyChain( primitive );
    }

    /**
     * Creates a relationship with the given id, from the nodes identified by id
     * and of type typeId
     *
     * @param id The id of the relationship to create.
     * @param type The id of the relationship type this relationship will
     *            have.
     * @param firstNodeId The id of the start node.
     * @param secondNodeId The id of the end node.
     */
    public void relationshipCreate( long id, int type, long firstNodeId, long secondNodeId )
    {
        // TODO could be unnecessary to mark as changed here already, dense nodes may not need to change
        NodeRecord firstNode = nodeRecords.getOrLoad( firstNodeId, null ).forChangingLinkage();
        if ( !firstNode.inUse() )
        {
            throw new IllegalStateException( "First node[" + firstNodeId +
                                             "] is deleted and cannot be used to create a relationship" );
        }
        NodeRecord secondNode = nodeRecords.getOrLoad( secondNodeId, null ).forChangingLinkage();
        if ( !secondNode.inUse() )
        {
            throw new IllegalStateException( "Second node[" + secondNodeId +
                                             "] is deleted and cannot be used to create a relationship" );
        }
        convertNodeToDenseIfNecessary( firstNode );
        convertNodeToDenseIfNecessary( secondNode );
        RelationshipRecord record = relRecords.create( id, null ).forChangingLinkage();
        record.setLinks( firstNodeId, secondNodeId, type );
        record.setInUse( true );
        record.setCreated();
        connectRelationship( firstNode, secondNode, record );
    }
    
    private void convertNodeToDenseIfNecessary( NodeRecord node )
    {
        if ( node.isDense() )
        {
            return;
        }
        long relId = node.getNextRel();
        if ( relId != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            RecordChange<Long, RelationshipRecord, Void> relChange = relRecords.getOrLoad( relId, null );
            RelationshipRecord rel = relChange.forReadingLinkage();
            if ( relCount( node.getId(), rel ) >= neoStore.getDenseNodeThreshold() )
            {
                convertNodeToDenseNode( node, relChange.forChangingLinkage() );
            }
        }
    }

    private void convertNodeToDenseNode( NodeRecord node, RelationshipRecord firstRel )
    {
        firstRel = relRecords.getOrLoad( firstRel.getId(), null ).forChangingLinkage();
        node.setDense( true );
        node.setNextRel( Record.NO_NEXT_RELATIONSHIP.intValue() );
        long relId = firstRel.getId();
        RelationshipRecord relRecord = firstRel;
        while ( relId != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            getWriteLock( new LockableRelationship( relId ) );
            relId = relChain( relRecord, node.getId() ).get( relRecord );
            connectRelationshipToDenseNode( node, relRecord );
            if ( relId == Record.NO_NEXT_RELATIONSHIP.intValue() )
            {
                break;
            }
            relRecord = relRecords.getOrLoad( relId, null ).forChangingLinkage();
        }
        if ( upgradedDenseNodes == null )
        {
            upgradedDenseNodes = new ArrayList<>();
        }
        upgradedDenseNodes.add( node );
    }

    private void connectRelationship( NodeRecord firstNode,
                                      NodeRecord secondNode, RelationshipRecord rel )
    {
        // Assertion interpreted: if node is a normal node and we're trying to create a
        // relationship that we already have as first rel for that node --> error
        assert firstNode.getNextRel() != rel.getId() || firstNode.isDense();
        assert secondNode.getNextRel() != rel.getId() || secondNode.isDense();

        if ( !firstNode.isDense() )
        {
            rel.setFirstNextRel( firstNode.getNextRel() );
        }
        if ( !secondNode.isDense() )
        {
            rel.setSecondNextRel( secondNode.getNextRel() );
        }

        if ( !firstNode.isDense() )
        {
            connect( firstNode, rel );
        }
        else
        {
            connectRelationshipToDenseNode( firstNode, rel );
        }

        if ( !secondNode.isDense() )
        {
            if ( firstNode.getId() != secondNode.getId() )
            {
                connect( secondNode, rel );
            }
            else
            {
                rel.setFirstInFirstChain( true );
                rel.setSecondPrevRel( rel.getFirstPrevRel() );
            }
        }
        else if ( firstNode.getId() != secondNode.getId() )
        {
            connectRelationshipToDenseNode( secondNode, rel );
        }

        if ( !firstNode.isDense() )
        {
            firstNode.setNextRel( rel.getId() );
        }
        if ( !secondNode.isDense() )
        {
            secondNode.setNextRel( rel.getId() );
        }
    }
    
    private void connectRelationshipToDenseNode( NodeRecord node, RelationshipRecord rel )
    {
        RelationshipGroupRecord group = getOrCreateRelationshipGroup( node, rel.getType() ).forChangingData();
        DirectionWrapper dir = wrapDirection( rel, node );
        long nextRel = dir.getNextRel( group );
        setCorrectNextRel( node, rel, nextRel );
        connect( node.getId(), nextRel, rel );
        dir.setNextRel( group, rel.getId() );
    }

    private void connect( NodeRecord node, RelationshipRecord rel )
    {
        connect( node.getId(), node.getNextRel(), rel );
    }

    private void connect( long nodeId, long firstRelId, RelationshipRecord rel )
    {
        long newCount = 1;
        if ( firstRelId != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            Relationship lockableRel = new LockableRelationship( firstRelId );
            getWriteLock( lockableRel );
            RelationshipRecord firstRel = relRecords.getOrLoad( firstRelId, null ).forChangingLinkage();
            boolean changed = false;
            if ( firstRel.getFirstNode() == nodeId )
            {
                newCount = firstRel.getFirstPrevRel()+1;
                firstRel.setFirstPrevRel( rel.getId() );
                firstRel.setFirstInFirstChain( false );
                changed = true;
            }
            if ( firstRel.getSecondNode() == nodeId )
            {
                newCount = firstRel.getSecondPrevRel()+1;
                firstRel.setSecondPrevRel( rel.getId() );
                firstRel.setFirstInSecondChain( false );
                changed = true;
            }
            if ( !changed )
            {
                throw new InvalidRecordException( nodeId + " doesn't match " + firstRel );
            }
        }

        // Set the relationship count
        if ( rel.getFirstNode() == nodeId )
        {
            rel.setFirstPrevRel( newCount );
            rel.setFirstInFirstChain( true );
        }
        if ( rel.getSecondNode() == nodeId )
        {
            rel.setSecondPrevRel( newCount );
            rel.setFirstInSecondChain( true );
        }
    }    

    /**
     * Creates a node for the given id
     *
     * @param nodeId The id of the node to create.
     */
    public void nodeCreate( long nodeId )
    {
        NodeRecord nodeRecord = nodeRecords.create( nodeId, null ).forChangingData();
        nodeRecord.setInUse( true );
        nodeRecord.setCreated();
    }

    /**
     * Creates a property index entry out of the given id and string.
     *
     * @param key The key of the property index, as a string.
     * @param id The property index record id.
     */
    public void createPropertyKeyToken( String key, int id )
    {
        PropertyKeyTokenRecord record = new PropertyKeyTokenRecord( id );
        record.setInUse( true );
        record.setCreated();
        PropertyKeyTokenStore propIndexStore = getPropertyStore().getPropertyKeyTokenStore();
        Collection<DynamicRecord> nameRecords =
                propIndexStore.allocateNameRecords( encodeString( key ) );
        record.setNameId( (int) first( nameRecords ).getId() );
        record.addNameRecords( nameRecords );
        addPropertyKeyTokenRecord( record );
    }

    /**
     * Creates a property index entry out of the given id and string.
     *
     * @param name The key of the property index, as a string.
     * @param id The property index record id.
     */
    public void createLabelToken( String name, int id )
    {
        LabelTokenRecord record = new LabelTokenRecord( id );
        record.setInUse( true );
        record.setCreated();
        LabelTokenStore labelTokenStore = getLabelTokenStore();
        Collection<DynamicRecord> nameRecords =
                labelTokenStore.allocateNameRecords( encodeString( name ) );
        record.setNameId( (int) first( nameRecords ).getId() );
        record.addNameRecords( nameRecords );
        addLabelIdRecord( record );
    }

    /**
     * Creates a new RelationshipType record with the given id that has the
     * given name.
     *
     * @param id The id of the new relationship type record.
     * @param name The name of the relationship type.
     */
    public void createRelationshipTypeToken( int id, String name )
    {
        RelationshipTypeTokenRecord record = new RelationshipTypeTokenRecord( id );
        record.setInUse( true );
        record.setCreated();
        Collection<DynamicRecord> typeNameRecords =
                getRelationshipTypeStore().allocateNameRecords( encodeString( name ) );
        record.setNameId( (int) first( typeNameRecords ).getId() );
        record.addNameRecords( typeNameRecords );
        addRelationshipTypeRecord( record );
    }

    static class CommandSorter implements Comparator<Command>, Serializable
    {
        @Override
        public int compare( Command o1, Command o2 )
        {
            long id1 = o1.getKey();
            long id2 = o2.getKey();
            long diff = id1 - id2;
            if ( diff > Integer.MAX_VALUE )
            {
                return Integer.MAX_VALUE;
            }
            else if ( diff < Integer.MIN_VALUE )
            {
                return Integer.MIN_VALUE;
            }
            else
            {
                return (int) diff;
            }
        }

        @Override
        public boolean equals( Object o )
        {
            return o instanceof CommandSorter;
        }

        @Override
        public int hashCode()
        {
            return 3217;
        }
    }

    void addRelationshipTypeRecord( RelationshipTypeTokenRecord record )
    {
        if ( relationshipTypeTokenRecords == null )
        {
            relationshipTypeTokenRecords = new HashMap<>();
        }
        relationshipTypeTokenRecords.put( record.getId(), record );
    }

    void addLabelIdRecord( LabelTokenRecord record )
    {
        if ( labelTokenRecords == null )
        {
            labelTokenRecords = new HashMap<>();
        }
        labelTokenRecords.put( record.getId(), record );
    }

    void addPropertyKeyTokenRecord( PropertyKeyTokenRecord record )
    {
        if ( propertyKeyTokenRecords == null )
        {
            propertyKeyTokenRecords = new HashMap<>();
        }
        propertyKeyTokenRecords.put( record.getId(), record );
    }

    private static class LockableRelationship implements Relationship
    {
        private final long id;

        LockableRelationship( long id )
        {
            this.id = id;
        }

        @Override
        public void delete()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public Node getEndNode()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public long getId()
        {
            return this.id;
        }

        @Override
        public GraphDatabaseService getGraphDatabase()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public Node[] getNodes()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public Node getOtherNode( Node node )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public Object getProperty( String key )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public Object getProperty( String key, Object defaultValue )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public Iterable<String> getPropertyKeys()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public Node getStartNode()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public RelationshipType getType()
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public boolean isType( RelationshipType type )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public boolean hasProperty( String key )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public Object removeProperty( String key )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public void setProperty( String key, Object value )
        {
            throw new UnsupportedOperationException( "Lockable rel" );
        }

        @Override
        public boolean equals( Object o )
        {
            return o instanceof Relationship && this.getId() == ((Relationship) o).getId();
        }

        @Override
        public int hashCode()
        {
            return (int) ((id >>> 32) ^ id);
        }

        @Override
        public String toString()
        {
            return "Lockable relationship #" + this.getId();
        }
    }

    private boolean assertPropertyChain( PrimitiveRecord primitive )
    {
        List<PropertyRecord> toCheck = new LinkedList<>();
        long nextIdToFetch = primitive.getNextProp();
        while ( nextIdToFetch != Record.NO_NEXT_PROPERTY.intValue() )
        {
            PropertyRecord propRecord = propertyRecords.getOrLoad( nextIdToFetch, primitive ).forReadingLinkage();
            toCheck.add( propRecord );
            assert propRecord.inUse() : primitive + "->"
                                        + Arrays.toString( toCheck.toArray() );
            nextIdToFetch = propRecord.getNextProp();
        }
        if ( toCheck.isEmpty() )
        {
            assert primitive.getNextProp() == Record.NO_NEXT_PROPERTY.intValue() : primitive;
            return true;
        }
        PropertyRecord first = toCheck.get( 0 );
        PropertyRecord last = toCheck.get( toCheck.size() - 1 );
        assert first.getPrevProp() == Record.NO_PREVIOUS_PROPERTY.intValue() : primitive
                                                                               + "->"
                                                                               + Arrays.toString( toCheck.toArray() );
        assert last.getNextProp() == Record.NO_NEXT_PROPERTY.intValue() : primitive
                                                                          + "->"
                                                                          + Arrays.toString( toCheck.toArray() );
        PropertyRecord current, previous = first;
        for ( int i = 1; i < toCheck.size(); i++ )
        {
            current = toCheck.get( i );
            assert current.getPrevProp() == previous.getId() : primitive
                                                               + "->"
                                                               + Arrays.toString( toCheck.toArray() );
            assert previous.getNextProp() == current.getId() : primitive
                                                               + "->"
                                                               + Arrays.toString( toCheck.toArray() );
            previous = current;
        }
        return true;
    }

    private RecordChange<Long, NeoStoreRecord, Void> getOrLoadNeoStoreRecord()
    {
        if ( neoStoreRecord == null )
        {
            neoStoreRecord = new RecordChanges<>( new RecordChanges.Loader<Long, NeoStoreRecord, Void>()
            {
                @Override
                public NeoStoreRecord newUnused( Long key, Void additionalData )
                {
                    throw new UnsupportedOperationException();
                }

                @Override
                public NeoStoreRecord load( Long key, Void additionalData )
                {
                    return neoStore.asRecord();
                }

                @Override
                public void ensureHeavy( NeoStoreRecord record )
                {
                }

                @Override
                public NeoStoreRecord clone(NeoStoreRecord neoStoreRecord) {
                    // We do not expect to manage the before state, so this operation will not be called.
                    throw new UnsupportedOperationException("Clone on NeoStoreRecord");
                }
            }, false );
        }
        return neoStoreRecord.getOrLoad( 0L, null );
    }

    /**
     * Adds a property to the graph, with the given index and value.
     *
     * @param propertyKey The index of the key of the property to add.
     * @param value The value of the property.
     * @return The added property, as a PropertyData object.
     */
    public DefinedProperty graphAddProperty( int propertyKey, Object value )
    {
        PropertyBlock block = new PropertyBlock();
        /*
         * Encoding has to be set here before anything is changed,
         * since an exception could be thrown in encodeValue now and tx not marked
         * rollback only.
         */
        getPropertyStore().encodeValue( block, propertyKey, value );
        RecordChange<Long, NeoStoreRecord, Void> change = getOrLoadNeoStoreRecord();
        addPropertyBlockToPrimitive( block, change );
        assert assertPropertyChain( change.forReadingLinkage() );
        return Property.property( propertyKey, value );
    }

    /**
     * Changes an existing property of the graph, with the given index to
     * the passed value
     *
     * @param propertyKey The index of the key of the property to change.
     * @param value The new value of the property.
     * @return The changed property, as a PropertyData object.
     */
    public DefinedProperty graphChangeProperty( int propertyKey, Object value )
    {
        return primitiveChangeProperty( getOrLoadNeoStoreRecord(), propertyKey, value );
    }

    /**
     * Removes the given property identified by indexKeyId of the graph with the
     * given id.
     *
     * @param propertyKey The index key of the property.
     */
    public void graphRemoveProperty( int propertyKey )
    {
        RecordChange<Long, NeoStoreRecord, Void> recordChange = getOrLoadNeoStoreRecord();
        removeProperty( recordChange.forReadingLinkage(), recordChange, propertyKey );
    }

    /**
     * Loads the complete property chain for the graph and returns it as a
     * map from property index id to property data.
     *
     * @param light If the properties should be loaded light or not.
     * @param records receiver of loaded properties.
     */
    public void graphLoadProperties( boolean light, PropertyReceiver records )
    {
        loadProperties( getPropertyStore(), neoStore.asRecord().getNextProp(), records );
    }

    public void createSchemaRule( SchemaRule schemaRule )
    {
        for(DynamicRecord change : schemaRuleChanges.create( schemaRule.getId(), schemaRule ).forChangingData())
        {
            change.setInUse( true );
            change.setCreated();
        }
    }

    public void dropSchemaRule( SchemaRule rule )
    {
        RecordChange<Long, Collection<DynamicRecord>, SchemaRule> change =
                schemaRuleChanges.getOrLoad(rule.getId(), rule);
        Collection<DynamicRecord> records = change.forChangingData();
        for ( DynamicRecord record : records )
        {
            record.setInUse( false );
        }
    }

    public void addLabelToNode( int labelId, long nodeId )
    {
        NodeRecord nodeRecord = nodeRecords.getOrLoad( nodeId, null ).forChangingData();
        parseLabelsField( nodeRecord ).add( labelId, getNodeStore() );
    }

    public void removeLabelFromNode( int labelId, long nodeId )
    {
        NodeRecord nodeRecord = nodeRecords.getOrLoad( nodeId, null ).forChangingData();
        parseLabelsField( nodeRecord ).remove( labelId, getNodeStore() );
    }

    public PrimitiveLongIterator getLabelsForNode( long nodeId )
    {
        // Don't consider changes in this transaction
        NodeRecord node = getNodeStore().getRecord( nodeId );
        return asPrimitiveIterator( parseLabelsField( node ).get( getNodeStore() ) );
    }

    public void setConstraintIndexOwner( IndexRule indexRule, long constraintId )
    {
        RecordChange<Long, Collection<DynamicRecord>, SchemaRule> change =
                schemaRuleChanges.getOrLoad( indexRule.getId(), indexRule );
        Collection<DynamicRecord> records = change.forChangingData();

        indexRule = indexRule.withOwningConstraint( constraintId );

        records.clear();
        records.addAll( getSchemaStore().allocateFrom( indexRule ) );
    }

    private static Pair<Map<DirectionWrapper, Iterable<RelationshipRecord>>, RelationshipLoadingPosition>
            getMoreRelationships( long nodeId, RelationshipLoadingPosition originalPosition, int grabSize,
                    DirectionWrapper direction, int[] types, RelationshipStore relStore )
    {
        // initialCapacity=grabSize saves the lists the trouble of resizing
        List<RelationshipRecord> out = new ArrayList<>();
        List<RelationshipRecord> in = new ArrayList<>();
        List<RelationshipRecord> loop = null;
        Map<DirectionWrapper, Iterable<RelationshipRecord>> result = new EnumMap<>( DirectionWrapper.class );
        result.put( DirectionWrapper.OUTGOING, out );
        result.put( DirectionWrapper.INCOMING, in );
        RelationshipLoadingPosition loadPosition = originalPosition.clone();
        long position = loadPosition.position( direction, types );
        for ( int i = 0; i < grabSize && position != Record.NO_NEXT_RELATIONSHIP.intValue(); i++ )
        {
            RelationshipRecord relRecord = relStore.getChainRecord( position );
            if ( relRecord == null )
            {
                // return what we got so far
                return Pair.of( result, loadPosition );
            }
            long firstNode = relRecord.getFirstNode();
            long secondNode = relRecord.getSecondNode();
            if ( relRecord.inUse() )
            {
                if ( firstNode == secondNode )
                {
                    if ( loop == null )
                    {
                        // This is done lazily because loops are probably quite
                        // rarely encountered
                        loop = new ArrayList<>();
                        result.put( DirectionWrapper.BOTH, loop );
                    }
                    loop.add( relRecord );
                }
                else if ( firstNode == nodeId )
                {
                    out.add( relRecord );
                }
                else if ( secondNode == nodeId )
                {
                    in.add( relRecord );
                }
            }
            else
            {
                i--;
            }

            long next = 0;
            if ( firstNode == nodeId )
            {
                next = relRecord.getFirstNextRel();
            }
            else if ( secondNode == nodeId )
            {
                next = relRecord.getSecondNextRel();
            }
            else
            {
                throw new InvalidRecordException( "Node[" + nodeId +
                        "] is neither firstNode[" + firstNode +
                        "] nor secondNode[" + secondNode + "] for Relationship[" + relRecord.getId() + "]" );
            }
            position = loadPosition.nextPosition( next, direction, types );
        }
        return Pair.of( result, loadPosition );
    }

    private static void loadPropertyChain( Collection<PropertyRecord> chain, PropertyStore propertyStore,
                                   PropertyReceiver receiver )
    {
        if ( chain != null )
        {
            for ( PropertyRecord propRecord : chain )
            {
                for ( PropertyBlock propBlock : propRecord.getPropertyBlocks() )
                {
                    receiver.receive( propBlock.newPropertyData( propertyStore ), propRecord.getId() );
                }
            }
        }
    }

    static void loadProperties(
            PropertyStore propertyStore, long nextProp, PropertyReceiver receiver )
    {
        Collection<PropertyRecord> chain = propertyStore.getPropertyRecordChain( nextProp );
        if ( chain != null )
        {
            loadPropertyChain( chain, propertyStore, receiver );
        }
    }
    
    static Map<Integer, RelationshipGroupRecord> loadRelationshipGroups( long firstGroup, RelationshipGroupStore store )
    {
        long groupId = firstGroup;
        long previousGroupId = Record.NO_NEXT_RELATIONSHIP.intValue();
        Map<Integer, RelationshipGroupRecord> result = new HashMap<>();
        while ( groupId != Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            RelationshipGroupRecord record = store.getRecord( groupId );
            record.setPrev( previousGroupId );
            result.put( record.getType(), record );
            previousGroupId = groupId;
            groupId = record.getNext();
        }
        return result;
    }
    
    private Map<Integer, RelationshipGroupRecord> loadRelationshipGroups( NodeRecord node )
    {
        assert node.isDense();
        return loadRelationshipGroups( node.getNextRel(), getRelationshipGroupStore() );
    }

    public int getRelationshipCount( long id, int type, DirectionWrapper direction )
    {
        NodeRecord node = getNodeStore().getRecord( id );
        long nextRel = node.getNextRel();
        if ( nextRel == Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            return 0;
        }
        if ( !node.isDense() )
        {
            assert type == -1;
            assert direction == DirectionWrapper.BOTH;
            return getRelationshipCount( node, nextRel );
        }

        // From here on it's only dense node specific

        Map<Integer, RelationshipGroupRecord> groups = loadRelationshipGroups( node );
        if ( type == -1 && direction == DirectionWrapper.BOTH )
        {   // Count for all types/directions
            int count = 0;
            for ( RelationshipGroupRecord group : groups.values() )
            {
                count += getRelationshipCount( node, group.getFirstOut() );
                count += getRelationshipCount( node, group.getFirstIn() );
                count += getRelationshipCount( node, group.getFirstLoop() );
            }
            return count;
        }
        else if ( type == -1 )
        {   // Count for all types with a given direction
            int count = 0;
            for ( RelationshipGroupRecord group : groups.values() )
            {
                count += getRelationshipCount( node, group, direction );
            }
            return count;
        }
        else if ( direction == DirectionWrapper.BOTH )
        {   // Count for a type
            RelationshipGroupRecord group = groups.get( type );
            if ( group == null )
            {
                return 0;
            }
            int count = 0;
            count += getRelationshipCount( node, group.getFirstOut() );
            count += getRelationshipCount( node, group.getFirstIn() );
            count += getRelationshipCount( node, group.getFirstLoop() );
            return count;
        }
        else
        {   // Count for one type and direction
            RelationshipGroupRecord group = groups.get( type );
            if ( group == null )
            {
                return 0;
            }
            return getRelationshipCount( node, group, direction );
        }
    }

    private int getRelationshipCount( NodeRecord node, RelationshipGroupRecord group, DirectionWrapper direction )
    {
        if ( direction == DirectionWrapper.BOTH )
        {
            return getRelationshipCount( node, DirectionWrapper.OUTGOING.getNextRel( group ) ) +
                    getRelationshipCount( node, DirectionWrapper.INCOMING.getNextRel( group ) ) +
                    getRelationshipCount( node, DirectionWrapper.BOTH.getNextRel( group ) );
        }
        
        return getRelationshipCount( node, direction.getNextRel( group ) ) +
                getRelationshipCount( node, DirectionWrapper.BOTH.getNextRel( group ) );
    }
    
    private int getRelationshipCount( NodeRecord node, long relId )
    {   // Relationship count is in a PREV field of the first record in a chain
        if ( relId == Record.NO_NEXT_RELATIONSHIP.intValue() )
        {
            return 0;
        }
        RelationshipRecord rel = getRelationshipStore().getRecord( relId );
        return (int) (node.getId() == rel.getFirstNode() ? rel.getFirstPrevRel() : rel.getSecondPrevRel());
    }


    public Integer[] getRelationshipTypes( long id )
    {
        Map<Integer, RelationshipGroupRecord> groups = loadRelationshipGroups( getNodeStore().getRecord( id ) );
        Integer[] types = new Integer[groups.size()];
        int i = 0;
        for ( Integer type : groups.keySet() )
        {
            types[i++] = type;
        }
        return types;
    }

    public RelationshipLoadingPosition getRelationshipChainPosition( long id )
    {
        RecordChange<Long, NodeRecord, Void> nodeChange = nodeRecords.getIfLoaded( id );
        if ( nodeChange != null && nodeChange.isCreated() )
        {
            return RelationshipLoadingPosition.EMPTY;
        }
        
        NodeRecord node = getNodeStore().getRecord( id );
        if ( node.isDense() )
        {
            long firstGroup = node.getNextRel();
            if ( firstGroup == Record.NO_NEXT_RELATIONSHIP.intValue() )
            {
                return RelationshipLoadingPosition.EMPTY;
            }
            Map<Integer, RelationshipGroupRecord> groups = loadRelationshipGroups( firstGroup,
                    neoStore.getRelationshipGroupStore() );
            return new DenseNodeChainPosition( groups );
        }
        
        long firstRel = node.getNextRel();
        return firstRel == Record.NO_NEXT_RELATIONSHIP.intValue() ?
                RelationshipLoadingPosition.EMPTY : new SingleChainPosition( firstRel );
    }
    
    private static RelationshipConnection relChain( RelationshipRecord rel, long nodeId )
    {
        if ( rel.getFirstNode() == nodeId )
        {
            return RelationshipConnection.START_NEXT;
        }
        if ( rel.getSecondNode() == nodeId )
        {
            return RelationshipConnection.END_NEXT;
        }
        throw new RuntimeException( nodeId + " neither start not end node in " + rel );
    }
    
    private void setCorrectNextRel( NodeRecord node, RelationshipRecord rel, long nextRel )
    {
        if ( node.getId() == rel.getFirstNode() )
        {
            rel.setFirstNextRel( nextRel );
        }
        if ( node.getId() == rel.getSecondNode() )
        {
            rel.setSecondNextRel( nextRel );
        }
    }
    
    public interface PropertyReceiver
    {
        void receive( DefinedProperty property, long propertyRecordId );
    }
}
