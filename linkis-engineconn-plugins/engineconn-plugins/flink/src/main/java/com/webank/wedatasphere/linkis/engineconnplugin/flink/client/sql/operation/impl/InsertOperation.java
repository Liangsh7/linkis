/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.webank.wedatasphere.linkis.engineconnplugin.flink.client.sql.operation.impl;

import com.webank.wedatasphere.linkis.engineconnplugin.flink.client.context.ExecutionContext;
import com.webank.wedatasphere.linkis.engineconnplugin.flink.client.deployment.ClusterDescriptorAdapterFactory;
import com.webank.wedatasphere.linkis.engineconnplugin.flink.client.sql.operation.AbstractJobOperation;
import com.webank.wedatasphere.linkis.engineconnplugin.flink.client.sql.operation.JobInfo;
import com.webank.wedatasphere.linkis.engineconnplugin.flink.client.sql.operation.result.ColumnInfo;
import com.webank.wedatasphere.linkis.engineconnplugin.flink.context.FlinkEngineConnContext;
import com.webank.wedatasphere.linkis.engineconnplugin.flink.exception.JobExecutionException;
import com.webank.wedatasphere.linkis.engineconnplugin.flink.exception.SqlExecutionException;
import com.webank.wedatasphere.linkis.engineconnplugin.flink.listener.RowsType;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.types.logical.BigIntType;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Operation for INSERT command.
 */
public class InsertOperation extends AbstractJobOperation {
    private static final Logger LOG = LoggerFactory.getLogger(InsertOperation.class);

    private final String statement;
    private final List<ColumnInfo> columnInfos;

    private boolean fetched = false;

    public InsertOperation(FlinkEngineConnContext context, String statement, String tableIdentifier) {
        super(context);
        this.statement = statement;
        this.columnInfos = Collections.singletonList(
                ColumnInfo.create(tableIdentifier, new BigIntType(false)));
    }

    @Override
    protected JobInfo submitJob() throws SqlExecutionException, JobExecutionException {
        JobID jobId = executeUpdateInternal(context.getExecutionContext());
        String applicationId = this.clusterDescriptorAdapter.getClusterID().toString();
        String webInterfaceUrl = this.clusterDescriptorAdapter.getWebInterfaceUrl();
        return new JobInfoImpl(jobId, applicationId, webInterfaceUrl);
    }

    @Override
    protected Optional<Tuple2<List<Row>, List<Boolean>>> fetchJobResults() throws JobExecutionException {
        if (fetched) {
            return Optional.empty();
        } else {
            // for session mode, we can get job status from JM, because JM is a long life service.
            // while for per-job mode, JM will be also destroy after the job is finished.
            // so it's hard to say whether the job is finished/canceled
            // or the job status is just inaccessible at that moment.
            // currently only yarn-per-job is supported,
            // and if the exception (thrown when getting job status) contains ApplicationNotFoundException,
            // we can say the job is finished.
            boolean isGloballyTerminalState = clusterDescriptorAdapter.isGloballyTerminalState();
            if (isGloballyTerminalState) {
                // TODO get affected_row_count for batch job
                fetched = true;
                return Optional.of(Tuple2.of(Collections.singletonList(
                        Row.of((long) Statement.SUCCESS_NO_INFO)), null));
            } else {
                // TODO throws exception if the job fails
                return Optional.of(Tuple2.of(Collections.emptyList(), null));
            }
        }
    }

    @Override
    protected List<ColumnInfo> getColumnInfos() {
        return columnInfos;
    }

    @Override
    protected void cancelJobInternal() throws JobExecutionException {
        clusterDescriptorAdapter.cancelJob();
    }

    private JobID executeUpdateInternal(ExecutionContext executionContext) throws SqlExecutionException, JobExecutionException {
        TableEnvironment tableEnv = executionContext.getTableEnvironment();
        // parse and validate statement
        TableResult tableResult;
        try {
            tableResult = executionContext.wrapClassLoader(() -> tableEnv.executeSql(statement));
        } catch (Exception t) {
            LOG.error(String.format("Invalid SQL query, sql is: %s.", statement), t);
            // catch everything such that the statement does not crash the executor
            throw new SqlExecutionException("Invalid SQL statement.", t);
        }
        tableResult.collect();
        asyncNotify(tableResult);
        JobID jobId = tableResult.getJobClient().get().getJobID();
        this.clusterDescriptorAdapter =
            ClusterDescriptorAdapterFactory.create(context.getExecutionContext(), jobId);
        clusterDescriptorAdapter.deployCluster(null, null);
        return jobId;
    }

    protected void asyncNotify(TableResult tableResult) {
        CompletableFuture.completedFuture(tableResult)
            .thenAccept(result -> {
                CloseableIterator<Row> iterator = result.collect();
                int affected = 0;
                while(iterator.hasNext()) {
                    Row row = iterator.next();
                    affected = (int) row.getField(0);
                }
                int finalAffected = affected;
                getFlinkStatusListeners().forEach(listener -> listener.onSuccess(finalAffected, RowsType.Affected()));
            }).whenComplete((unused, throwable) -> getFlinkStatusListeners().forEach(listener -> listener.onFailed("Error while submitting job.", throwable)));
    }

}
