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

package org.apache.flink.streaming.api.operators.python;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.memory.ManagedMemoryUseCase;
import org.apache.flink.fnexecution.v1.FlinkFnApi;
import org.apache.flink.python.PythonFunctionRunner;
import org.apache.flink.runtime.state.VoidNamespace;
import org.apache.flink.runtime.state.VoidNamespaceSerializer;
import org.apache.flink.streaming.api.TimeDomain;
import org.apache.flink.streaming.api.functions.python.DataStreamPythonFunctionInfo;
import org.apache.flink.streaming.api.operators.InternalTimer;
import org.apache.flink.streaming.api.operators.InternalTimerService;
import org.apache.flink.streaming.api.operators.TimestampedCollector;
import org.apache.flink.streaming.api.operators.Triggerable;
import org.apache.flink.streaming.api.operators.python.collector.RunnerOutputCollector;
import org.apache.flink.streaming.api.operators.python.timer.TimerHandler;
import org.apache.flink.streaming.api.operators.python.timer.TimerRegistration;
import org.apache.flink.streaming.api.runners.python.beam.BeamDataStreamPythonFunctionRunner;
import org.apache.flink.streaming.api.utils.ProtoUtils;
import org.apache.flink.streaming.api.utils.PythonTypeUtils;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.types.Row;

import java.util.Collections;

import static org.apache.flink.python.Constants.STATEFUL_FUNCTION_URN;
import static org.apache.flink.streaming.api.operators.python.timer.TimerUtils.createTimerDataCoderInfoDescriptorProto;
import static org.apache.flink.streaming.api.operators.python.timer.TimerUtils.createTimerDataTypeInfo;
import static org.apache.flink.streaming.api.utils.ProtoUtils.createRawTypeCoderInfoDescriptorProto;
import static org.apache.flink.streaming.api.utils.PythonOperatorUtils.inBatchExecutionMode;

/** KeyedCoProcessOperator. */
@Internal
public class PythonKeyedCoProcessOperator<OUT>
        extends TwoInputPythonFunctionOperator<Row, Row, Row, OUT>
        implements ResultTypeQueryable<OUT>, Triggerable<Row, VoidNamespace> {

    private static final long serialVersionUID = 1L;

    /** The TypeInformation of current key. */
    private final TypeInformation<Row> keyTypeInfo;

    private final TypeInformation<OUT> outputTypeInfo;

    /** TimerService for current operator to register or fire timer. */
    private transient InternalTimerService<VoidNamespace> internalTimerService;

    /** Serializer for current key. */
    private transient TypeSerializer<Row> keyTypeSerializer;

    private transient TypeSerializer<Row> timerDataSerializer;

    /** The TypeInformation of timer data. */
    private transient TypeInformation<Row> timerDataTypeInfo;

    private transient RunnerInputHandler runnerInputHandler;
    private transient RunnerOutputCollector<OUT> runnerOutputCollector;
    private transient TimerHandler timerHandler;

    private transient Object keyForTimerService;

    public PythonKeyedCoProcessOperator(
            Configuration config,
            TypeInformation<Row> inputTypeInfo1,
            TypeInformation<Row> inputTypeInfo2,
            TypeInformation<OUT> outputTypeInfo,
            DataStreamPythonFunctionInfo pythonFunctionInfo) {
        super(
                config,
                pythonFunctionInfo,
                RunnerInputHandler.getRunnerInputTypeInfo(inputTypeInfo1, inputTypeInfo2),
                RunnerOutputCollector.getRunnerOutputTypeInfo(outputTypeInfo));
        this.keyTypeInfo = constructKeyTypeInfo(inputTypeInfo1);
        this.outputTypeInfo = outputTypeInfo;
    }

    @Override
    public void open() throws Exception {
        internalTimerService =
                getInternalTimerService("user-timers", VoidNamespaceSerializer.INSTANCE, this);
        timerDataTypeInfo = createTimerDataTypeInfo(keyTypeInfo);

        keyTypeSerializer =
                PythonTypeUtils.TypeInfoToSerializerConverter.typeInfoSerializerConverter(
                        keyTypeInfo);
        timerDataSerializer =
                PythonTypeUtils.TypeInfoToSerializerConverter.typeInfoSerializerConverter(
                        timerDataTypeInfo);

        runnerInputHandler = new RunnerInputHandler();
        runnerOutputCollector = new RunnerOutputCollector<>(new TimestampedCollector<>(output));
        timerHandler = new TimerHandler();

        super.open();
    }

    @Override
    public PythonFunctionRunner createPythonFunctionRunner() throws Exception {
        return new BeamDataStreamPythonFunctionRunner(
                getRuntimeContext().getTaskName(),
                createPythonEnvironmentManager(),
                STATEFUL_FUNCTION_URN,
                ProtoUtils.getUserDefinedDataStreamStatefulFunctionProto(
                        getPythonFunctionInfo(),
                        getRuntimeContext(),
                        Collections.emptyMap(),
                        keyTypeInfo,
                        inBatchExecutionMode(getKeyedStateBackend())),
                getJobOptions(),
                getFlinkMetricContainer(),
                getKeyedStateBackend(),
                keyTypeSerializer,
                null,
                new TimerRegistration(
                        getKeyedStateBackend(),
                        internalTimerService,
                        this,
                        VoidNamespaceSerializer.INSTANCE,
                        PythonTypeUtils.TypeInfoToSerializerConverter.typeInfoSerializerConverter(
                                timerDataTypeInfo)),
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
                createTimerDataCoderInfoDescriptorProto(timerDataTypeInfo));
    }

    @Override
    public void processElement1(StreamRecord<Row> element) throws Exception {
        processElement(true, element);
    }

    @Override
    public void processElement2(StreamRecord<Row> element) throws Exception {
        processElement(false, element);
    }

    @Override
    public void emitResult(Tuple2<byte[], Integer> resultTuple) throws Exception {
        byte[] rawResult = resultTuple.f0;
        int length = resultTuple.f1;
        bais.setBuffer(rawResult, 0, length);
        Row runnerOutput = getRunnerOutputTypeSerializer().deserialize(baisWrapper);
        runnerOutputCollector.collect(runnerOutput);
    }

    @Override
    public TypeInformation<OUT> getProducedType() {
        return outputTypeInfo;
    }

    @Override
    public void onEventTime(InternalTimer<Row, VoidNamespace> timer) throws Exception {
        processTimer(TimeDomain.EVENT_TIME, timer);
    }

    @Override
    public void onProcessingTime(InternalTimer<Row, VoidNamespace> timer) throws Exception {
        processTimer(TimeDomain.PROCESSING_TIME, timer);
    }

    /**
     * It is responsible to send timer data to python worker when a registered timer is fired. The
     * input data is a Row containing 4 fields: TimerFlag 0 for proc time, 1 for event time;
     * Timestamp of the fired timer; Current watermark and the key of the timer.
     *
     * @param timeDomain The type of the timer.
     * @param timer The fired timer.
     * @throws Exception The runnerInputSerializer might throw exception.
     */
    private void processTimer(TimeDomain timeDomain, InternalTimer<Row, VoidNamespace> timer)
            throws Exception {
        Row timerData =
                timerHandler.buildTimerData(
                        timeDomain,
                        internalTimerService.currentWatermark(),
                        timer.getTimestamp(),
                        timer.getKey(),
                        null);
        timerDataSerializer.serialize(timerData, baosWrapper);
        pythonFunctionRunner.processTimer(baos.toByteArray());
        baos.reset();
        elementCount++;
        checkInvokeFinishBundleByCount();
        emitResults();
    }

    private void processElement(boolean isLeft, StreamRecord<Row> element) throws Exception {
        Row row =
                runnerInputHandler.buildRunnerInputData(
                        isLeft,
                        element.getTimestamp(),
                        internalTimerService.currentWatermark(),
                        element.getValue());
        getRunnerInputTypeSerializer().serialize(row, baosWrapper);
        pythonFunctionRunner.process(baos.toByteArray());
        baos.reset();
        elementCount++;
        checkInvokeFinishBundleByCount();
        emitResults();
    }

    private static TypeInformation<Row> constructKeyTypeInfo(TypeInformation<Row> inputTypeInfo) {
        return new RowTypeInfo(((RowTypeInfo) inputTypeInfo).getTypeAt(0));
    }

    /**
     * As the beam state gRPC service will access the KeyedStateBackend in parallel with this
     * operator, we must override this method to prevent changing the current key of the
     * KeyedStateBackend while the beam service is handling requests.
     */
    @Override
    public void setCurrentKey(Object key) {
        if (inBatchExecutionMode(getKeyedStateBackend())) {
            super.setCurrentKey(key);
        }

        keyForTimerService = key;
    }

    @Override
    public Object getCurrentKey() {
        return keyForTimerService;
    }

    @Override
    public FlinkFnApi.CoderInfoDescriptor createInputCoderInfoDescriptor(
            TypeInformation<?> runnerInputType) {
        return createRawTypeCoderInfoDescriptorProto(
                runnerInputType, FlinkFnApi.CoderInfoDescriptor.Mode.MULTIPLE, false);
    }

    @Override
    public FlinkFnApi.CoderInfoDescriptor createOutputCoderInfoDescriptor(
            TypeInformation<?> runnerOutType) {
        return createRawTypeCoderInfoDescriptorProto(
                runnerOutType, FlinkFnApi.CoderInfoDescriptor.Mode.MULTIPLE, false);
    }
}
