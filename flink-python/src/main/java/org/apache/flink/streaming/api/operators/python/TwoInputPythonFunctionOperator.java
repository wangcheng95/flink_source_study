/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.operators.python;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.memory.ByteArrayInputStreamWithPos;
import org.apache.flink.core.memory.ByteArrayOutputStreamWithPos;
import org.apache.flink.core.memory.DataInputViewStreamWrapper;
import org.apache.flink.core.memory.DataOutputViewStreamWrapper;
import org.apache.flink.core.memory.ManagedMemoryUseCase;
import org.apache.flink.fnexecution.v1.FlinkFnApi;
import org.apache.flink.python.PythonFunctionRunner;
import org.apache.flink.streaming.api.functions.python.DataStreamPythonFunctionInfo;
import org.apache.flink.streaming.api.operators.TimestampedCollector;
import org.apache.flink.streaming.api.runners.python.beam.BeamDataStreamPythonFunctionRunner;
import org.apache.flink.table.functions.python.PythonEnv;
import org.apache.flink.types.Row;

import java.util.Collections;
import java.util.Map;

import static org.apache.flink.python.Constants.STATELESS_FUNCTION_URN;
import static org.apache.flink.streaming.api.utils.ProtoUtils.getUserDefinedDataStreamFunctionProto;
import static org.apache.flink.streaming.api.utils.PythonOperatorUtils.inBatchExecutionMode;
import static org.apache.flink.streaming.api.utils.PythonTypeUtils.TypeInfoToSerializerConverter.typeInfoSerializerConverter;

/**
 * {@link TwoInputPythonFunctionOperator} is responsible for launching beam runner which will start
 * a python harness to execute two-input user defined python function.
 */
@Internal
public abstract class TwoInputPythonFunctionOperator<IN1, IN2, RUNNER_OUT, OUT>
        extends AbstractTwoInputPythonFunctionOperator<IN1, IN2, OUT> {

    private static final long serialVersionUID = 1L;

    /** The options used to configure the Python worker process. */
    private final Map<String, String> jobOptions;

    /** The serialized python function to be executed. */
    private final DataStreamPythonFunctionInfo pythonFunctionInfo;

    /** The TypeInformation of python worker input data. */
    protected final TypeInformation<Row> runnerInputTypeInfo;

    protected final TypeInformation<RUNNER_OUT> runnerOutputTypeInfo;

    /** The TypeSerializer of python worker input data. */
    private final TypeSerializer<Row> runnerInputTypeSerializer;

    /** The TypeSerializer of the runner output. */
    private final TypeSerializer<RUNNER_OUT> runnerOutputTypeSerializer;

    protected transient ByteArrayInputStreamWithPos bais;

    protected transient DataInputViewStreamWrapper baisWrapper;

    protected transient ByteArrayOutputStreamWithPos baos;

    protected transient DataOutputViewStreamWrapper baosWrapper;

    protected transient TimestampedCollector<OUT> collector;

    protected transient Row reuseRow;

    public TwoInputPythonFunctionOperator(
            Configuration config,
            DataStreamPythonFunctionInfo pythonFunctionInfo,
            TypeInformation<Row> runnerInputTypeInfo,
            TypeInformation<RUNNER_OUT> runnerOutputTypeInfo) {
        super(config);
        this.jobOptions = config.toMap();
        this.pythonFunctionInfo = pythonFunctionInfo;
        this.runnerInputTypeInfo = runnerInputTypeInfo;
        this.runnerOutputTypeInfo = runnerOutputTypeInfo;
        this.runnerInputTypeSerializer = typeInfoSerializerConverter(runnerInputTypeInfo);
        this.runnerOutputTypeSerializer = typeInfoSerializerConverter(runnerOutputTypeInfo);
    }

    @Override
    public void open() throws Exception {
        bais = new ByteArrayInputStreamWithPos();
        baisWrapper = new DataInputViewStreamWrapper(bais);
        baos = new ByteArrayOutputStreamWithPos();
        baosWrapper = new DataOutputViewStreamWrapper(baos);

        collector = new TimestampedCollector<>(output);
        reuseRow = new Row(3);

        super.open();
    }

    @Override
    public PythonFunctionRunner createPythonFunctionRunner() throws Exception {
        return new BeamDataStreamPythonFunctionRunner(
                getRuntimeContext().getTaskName(),
                createPythonEnvironmentManager(),
                STATELESS_FUNCTION_URN,
                getUserDefinedDataStreamFunctionProto(
                        pythonFunctionInfo,
                        getRuntimeContext(),
                        Collections.emptyMap(),
                        inBatchExecutionMode(getKeyedStateBackend())),
                jobOptions,
                getFlinkMetricContainer(),
                null,
                null,
                null,
                null,
                getContainingTask().getEnvironment().getMemoryManager(),
                getOperatorConfig()
                        .getManagedMemoryFractionOperatorUseCaseOfSlot(
                                ManagedMemoryUseCase.PYTHON,
                                getContainingTask()
                                        .getEnvironment()
                                        .getTaskManagerInfo()
                                        .getConfiguration(),
                                getContainingTask()
                                        .getEnvironment()
                                        .getUserCodeClassLoader()
                                        .asClassLoader()),
                createInputCoderInfoDescriptor(runnerInputTypeInfo),
                createOutputCoderInfoDescriptor(runnerOutputTypeInfo),
                null);
    }

    @Override
    public PythonEnv getPythonEnv() {
        return pythonFunctionInfo.getPythonFunction().getPythonEnv();
    }

    public abstract FlinkFnApi.CoderInfoDescriptor createInputCoderInfoDescriptor(
            TypeInformation<?> runnerInputType);

    public abstract FlinkFnApi.CoderInfoDescriptor createOutputCoderInfoDescriptor(
            TypeInformation<?> runnerOutType);

    protected Map<String, String> getJobOptions() {
        return jobOptions;
    }

    protected DataStreamPythonFunctionInfo getPythonFunctionInfo() {
        return pythonFunctionInfo;
    }

    protected TypeSerializer<Row> getRunnerInputTypeSerializer() {
        return runnerInputTypeSerializer;
    }

    protected TypeSerializer<RUNNER_OUT> getRunnerOutputTypeSerializer() {
        return runnerOutputTypeSerializer;
    }

    /** RunnerInputHandler. */
    public static final class RunnerInputHandler {

        private final Row reusableElementData;
        private final Row reusableRunnerInput;

        public RunnerInputHandler() {
            this.reusableElementData = new Row(3);
            this.reusableRunnerInput = new Row(3);
            this.reusableRunnerInput.setField(2, reusableElementData);
        }

        public Row buildRunnerInputData(
                boolean isLeft, long timestamp, long watermark, Object elementData) {
            reusableElementData.setField(0, isLeft);
            if (isLeft) {
                // The input row is a tuple of key and value.
                reusableElementData.setField(1, elementData);
                // need to set null since it is a reuse row.
                reusableElementData.setField(2, null);
            } else {
                // need to set null since it is a reuse row.
                reusableElementData.setField(1, null);
                // The input row is a tuple of key and value.
                reusableElementData.setField(2, elementData);
            }

            reusableRunnerInput.setField(0, timestamp);
            reusableRunnerInput.setField(1, watermark);
            return reusableRunnerInput;
        }

        public static TypeInformation<Row> getRunnerInputTypeInfo(
                TypeInformation<?> leftInputType, TypeInformation<?> rightInputType) {
            // structure: [timestamp, watermark, [isLeft, leftInput, rightInput]]
            return Types.ROW(
                    Types.LONG,
                    Types.LONG,
                    new RowTypeInfo(Types.BOOLEAN, leftInputType, rightInputType));
        }
    }
}
