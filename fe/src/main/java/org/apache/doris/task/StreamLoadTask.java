/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.apache.doris.task;

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import org.apache.doris.analysis.ColumnSeparator;
import org.apache.doris.analysis.Expr;
import org.apache.doris.analysis.ImportColumnDesc;
import org.apache.doris.analysis.ImportColumnsStmt;
import org.apache.doris.analysis.ImportWhereStmt;
import org.apache.doris.analysis.SqlParser;
import org.apache.doris.analysis.SqlScanner;
import org.apache.doris.catalog.Catalog;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.UserException;
import org.apache.doris.load.RoutineLoadDesc;
import org.apache.doris.load.routineload.RoutineLoadJob;
import org.apache.doris.load.routineload.RoutineLoadManager;
import org.apache.doris.load.routineload.RoutineLoadTaskInfo;
import org.apache.doris.thrift.TFileFormatType;
import org.apache.doris.thrift.TFileType;
import org.apache.doris.thrift.TStreamLoadPutRequest;
import org.apache.doris.thrift.TUniqueId;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.StringReader;
import java.util.Map;
import java.util.UUID;

public class StreamLoadTask {

    private static final Logger LOG = LogManager.getLogger(StreamLoadTask.class);

    private TUniqueId id;
    private long txnId;
    private TFileType fileType;
    private TFileFormatType formatType;

    // optional
    private Map<String, Expr> columnToColumnExpr;
    private Expr whereExpr;
    private ColumnSeparator columnSeparator;
    private String partitions;
    private String path;

    public StreamLoadTask(TUniqueId id, long txnId, TFileType fileType, TFileFormatType formatType) {
        this.id = id;
        this.txnId = txnId;
        this.fileType = fileType;
        this.formatType = formatType;
    }

    public TUniqueId getId() {
        return id;
    }

    public long getTxnId() {
        return txnId;
    }

    public TFileType getFileType() {
        return fileType;
    }

    public TFileFormatType getFormatType() {
        return formatType;
    }

    public Map<String, Expr> getColumnToColumnExpr() {
        return columnToColumnExpr;
    }

    public Expr getWhereExpr() {
        return whereExpr;
    }

    public ColumnSeparator getColumnSeparator() {
        return columnSeparator;
    }

    public String getPartitions() {
        return partitions;
    }

    public String getPath() {
        return path;
    }

    public static StreamLoadTask fromTStreamLoadPutRequest(TStreamLoadPutRequest request) throws UserException {
        StreamLoadTask streamLoadTask = new StreamLoadTask(request.getLoadId(), request.getTxnId(),
                                                           request.getFileType(), request.getFormatType());
        streamLoadTask.setOptionalFromTSLPutRequest(request);
        return streamLoadTask;
    }

    private void setOptionalFromTSLPutRequest(TStreamLoadPutRequest request) throws UserException {
        if (request.isSetColumns()) {
            setColumnToColumnExpr(request.getColumns());
        }
        if (request.isSetWhere()) {
            setWhereExpr(request.getWhere());
        }
        if (request.isSetColumnSeparator()) {
            setColumnSeparator(request.getColumnSeparator());
        }
        if (request.isSetPartitions()) {
            partitions = request.getPartitions();
        }
        switch (request.getFileType()) {
            case FILE_LOCAL:
                path = request.getPath();
        }
    }

    // the taskId and txnId is faked
    public static StreamLoadTask fromRoutineLoadJob(RoutineLoadJob routineLoadJob) {
        UUID taskId = UUID.randomUUID();
        TUniqueId queryId = new TUniqueId(taskId.getMostSignificantBits(),
                                          taskId.getLeastSignificantBits());
        StreamLoadTask streamLoadTask = new StreamLoadTask(queryId, -1L,
                                                           TFileType.FILE_STREAM, TFileFormatType.FORMAT_CSV_PLAIN);
        streamLoadTask.setOptionalFromRoutineLoadJob(routineLoadJob);
        return streamLoadTask;
    }

    private void setOptionalFromRoutineLoadJob(RoutineLoadJob routineLoadJob) {
        if (routineLoadJob.getRoutineLoadDesc() != null) {
            RoutineLoadDesc routineLoadDesc = routineLoadJob.getRoutineLoadDesc();
            if (routineLoadDesc.getColumnsInfo() != null) {
                columnToColumnExpr = routineLoadDesc.getColumnsInfo().getParsedExprMap();
            }
            if (routineLoadDesc.getWherePredicate() != null) {
                whereExpr = routineLoadDesc.getWherePredicate();
            }
            if (routineLoadDesc.getColumnSeparator() != null) {
                columnSeparator = routineLoadDesc.getColumnSeparator();
            }
            if (routineLoadDesc.getPartitionNames() != null && routineLoadDesc.getPartitionNames().size() != 0) {
                partitions = Joiner.on(",").join(routineLoadDesc.getPartitionNames());
            }
        }
    }

    private void setColumnToColumnExpr(String columns) throws UserException {
        String columnsSQL = new String("COLUMNS " + columns);
        SqlParser parser = new SqlParser(new SqlScanner(new StringReader(columnsSQL)));
        ImportColumnsStmt columnsStmt;
        try {
            columnsStmt = (ImportColumnsStmt) parser.parse().value;
        } catch (Error e) {
            LOG.warn("error happens when parsing columns, sql={}", columnsSQL, e);
            throw new AnalysisException("failed to parsing columns' header, maybe contain unsupported character");
        } catch (AnalysisException e) {
            LOG.warn("analyze columns' statement failed, sql={}, error={}",
                     columnsSQL, parser.getErrorMsg(columnsSQL), e);
            String errorMessage = parser.getErrorMsg(columnsSQL);
            if (errorMessage == null) {
                throw e;
            } else {
                throw new AnalysisException(errorMessage, e);
            }
        } catch (Exception e) {
            LOG.warn("failed to parse columns header, sql={}", columnsSQL, e);
            throw new UserException("parse columns header failed", e);
        }

        if (columnsStmt.getColumns() != null || columnsStmt.getColumns().size() != 0) {
            columnToColumnExpr = Maps.newHashMap();
            for (ImportColumnDesc columnDesc : columnsStmt.getColumns()) {
                columnToColumnExpr.put(columnDesc.getColumn(), columnDesc.getExpr());
            }
        }
    }

    private void setWhereExpr(String whereString) throws UserException {
        String whereSQL = new String("WHERE " + whereString);
        SqlParser parser = new SqlParser(new SqlScanner(new StringReader(whereSQL)));
        ImportWhereStmt whereStmt;
        try {
            whereStmt = (ImportWhereStmt) parser.parse().value;
        } catch (Error e) {
            LOG.warn("error happens when parsing where header, sql={}", whereSQL, e);
            throw new AnalysisException("failed to parsing where header, maybe contain unsupported character");
        } catch (AnalysisException e) {
            LOG.warn("analyze where statement failed, sql={}, error={}",
                     whereSQL, parser.getErrorMsg(whereSQL), e);
            String errorMessage = parser.getErrorMsg(whereSQL);
            if (errorMessage == null) {
                throw e;
            } else {
                throw new AnalysisException(errorMessage, e);
            }
        } catch (Exception e) {
            LOG.warn("failed to parse where header, sql={}", whereSQL, e);
            throw new UserException("parse columns header failed", e);
        }
        whereExpr = whereStmt.getExpr();
    }

    private void setColumnSeparator(String oriSeparator) throws AnalysisException {
        columnSeparator = new ColumnSeparator(oriSeparator);
        columnSeparator.analyze();
    }
}