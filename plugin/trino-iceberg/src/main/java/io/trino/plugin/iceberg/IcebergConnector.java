/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.iceberg;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.airlift.bootstrap.LifeCycleManager;
import io.trino.plugin.base.classloader.ClassLoaderSafeConnectorMetadata;
import io.trino.plugin.base.session.SessionPropertiesProvider;
import io.trino.plugin.hive.HiveTransactionHandle;
import io.trino.spi.classloader.ThreadContextClassLoader;
import io.trino.spi.connector.Connector;
import io.trino.spi.connector.ConnectorAccessControl;
import io.trino.spi.connector.ConnectorCapabilities;
import io.trino.spi.connector.ConnectorHandleResolver;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorPageSinkProvider;
import io.trino.spi.connector.ConnectorPageSourceProvider;
import io.trino.spi.connector.ConnectorSplitManager;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.SystemTable;
import io.trino.spi.procedure.Procedure;
import io.trino.spi.session.PropertyMetadata;
import io.trino.spi.transaction.IsolationLevel;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Sets.immutableEnumSet;
import static io.trino.spi.connector.ConnectorCapabilities.NOT_NULL_COLUMN_CONSTRAINT;
import static io.trino.spi.transaction.IsolationLevel.SERIALIZABLE;
import static io.trino.spi.transaction.IsolationLevel.checkConnectorSupports;
import static java.util.Objects.requireNonNull;

public class IcebergConnector
        implements Connector
{
    private final LifeCycleManager lifeCycleManager;
    private final IcebergTransactionManager transactionManager;
    private final IcebergMetadataFactory metadataFactory;
    private final ConnectorSplitManager splitManager;
    private final ConnectorPageSourceProvider pageSourceProvider;
    private final ConnectorPageSinkProvider pageSinkProvider;
    private final Set<SystemTable> systemTables;
    private final List<PropertyMetadata<?>> sessionProperties;
    private final List<PropertyMetadata<?>> schemaProperties;
    private final List<PropertyMetadata<?>> tableProperties;
    private final Optional<ConnectorAccessControl> accessControl;
    private final Set<Procedure> procedures;

    public IcebergConnector(
            LifeCycleManager lifeCycleManager,
            IcebergTransactionManager transactionManager,
            IcebergMetadataFactory metadataFactory,
            ConnectorSplitManager splitManager,
            ConnectorPageSourceProvider pageSourceProvider,
            ConnectorPageSinkProvider pageSinkProvider,
            Set<SystemTable> systemTables,
            Set<SessionPropertiesProvider> sessionPropertiesProviders,
            List<PropertyMetadata<?>> schemaProperties,
            List<PropertyMetadata<?>> tableProperties,
            Optional<ConnectorAccessControl> accessControl,
            Set<Procedure> procedures)
    {
        this.lifeCycleManager = requireNonNull(lifeCycleManager, "lifeCycleManager is null");
        this.transactionManager = requireNonNull(transactionManager, "transactionManager is null");
        this.metadataFactory = requireNonNull(metadataFactory, "metadataFactory is null");
        this.splitManager = requireNonNull(splitManager, "splitManager is null");
        this.pageSourceProvider = requireNonNull(pageSourceProvider, "pageSourceProvider is null");
        this.pageSinkProvider = requireNonNull(pageSinkProvider, "pageSinkProvider is null");
        this.systemTables = ImmutableSet.copyOf(requireNonNull(systemTables, "systemTables is null"));
        this.sessionProperties = requireNonNull(sessionPropertiesProviders, "sessionPropertiesProviders is null").stream()
                .flatMap(sessionPropertiesProvider -> sessionPropertiesProvider.getSessionProperties().stream())
                .collect(toImmutableList());
        this.schemaProperties = ImmutableList.copyOf(requireNonNull(schemaProperties, "schemaProperties is null"));
        this.tableProperties = ImmutableList.copyOf(requireNonNull(tableProperties, "tableProperties is null"));
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
        this.procedures = requireNonNull(procedures, "procedures is null");
    }

    @Override
    public Optional<ConnectorHandleResolver> getHandleResolver()
    {
        return Optional.of(new IcebergHandleResolver());
    }

    @Override
    public Set<ConnectorCapabilities> getCapabilities()
    {
        return immutableEnumSet(NOT_NULL_COLUMN_CONSTRAINT);
    }

    @Override
    public ConnectorMetadata getMetadata(ConnectorTransactionHandle transaction)
    {
        ConnectorMetadata metadata = transactionManager.get(transaction);
        return new ClassLoaderSafeConnectorMetadata(metadata, getClass().getClassLoader());
    }

    @Override
    public ConnectorSplitManager getSplitManager()
    {
        return splitManager;
    }

    @Override
    public ConnectorPageSourceProvider getPageSourceProvider()
    {
        return pageSourceProvider;
    }

    @Override
    public ConnectorPageSinkProvider getPageSinkProvider()
    {
        return pageSinkProvider;
    }

    @Override
    public Set<SystemTable> getSystemTables()
    {
        return systemTables;
    }

    @Override
    public Set<Procedure> getProcedures()
    {
        return procedures;
    }

    @Override
    public List<PropertyMetadata<?>> getSessionProperties()
    {
        return sessionProperties;
    }

    @Override
    public List<PropertyMetadata<?>> getSchemaProperties()
    {
        return schemaProperties;
    }

    @Override
    public List<PropertyMetadata<?>> getTableProperties()
    {
        return tableProperties;
    }

    @Override
    public List<PropertyMetadata<?>> getMaterializedViewProperties()
    {
        return tableProperties;
    }

    @Override
    public ConnectorAccessControl getAccessControl()
    {
        return accessControl.orElseThrow(UnsupportedOperationException::new);
    }

    @Override
    public ConnectorTransactionHandle beginTransaction(IsolationLevel isolationLevel, boolean readOnly)
    {
        checkConnectorSupports(SERIALIZABLE, isolationLevel);
        ConnectorTransactionHandle transaction = new HiveTransactionHandle();
        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(getClass().getClassLoader())) {
            transactionManager.put(transaction, metadataFactory.create());
        }
        return transaction;
    }

    @Override
    public void commit(ConnectorTransactionHandle transaction)
    {
        transactionManager.remove(transaction);
    }

    @Override
    public void rollback(ConnectorTransactionHandle transaction)
    {
        IcebergMetadata metadata = transactionManager.remove(transaction);
        try (ThreadContextClassLoader ignored = new ThreadContextClassLoader(getClass().getClassLoader())) {
            metadata.rollback();
        }
    }

    @Override
    public final void shutdown()
    {
        lifeCycleManager.stop();
    }
}
