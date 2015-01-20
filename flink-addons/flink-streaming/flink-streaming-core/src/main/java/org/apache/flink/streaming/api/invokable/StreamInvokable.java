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

package org.apache.flink.streaming.api.invokable;

import java.io.IOException;
import java.io.Serializable;

import org.apache.flink.api.common.functions.Function;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.functions.util.FunctionUtils;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.collector.AbstractStreamCollector;
import org.apache.flink.streaming.api.ft.context.FTContext;
import org.apache.flink.streaming.api.invokable.ft.FailException;
import org.apache.flink.streaming.api.streamrecord.StreamRecord;
import org.apache.flink.streaming.api.streamrecord.StreamRecordSerializer;
import org.apache.flink.streaming.api.streamvertex.StreamTaskContext;
import org.apache.flink.util.MutableObjectIterator;
import org.apache.flink.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The StreamInvokable represents the base class for all invokables in the
 * streaming topology.
 *
 * @param <OUT> The output type of the invokable
 */
public abstract class StreamInvokable<IN, OUT> implements Serializable {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(StreamInvokable.class);

	protected StreamTaskContext<OUT> taskContext;

	protected MutableObjectIterator<StreamRecord<IN>> recordIterator;
	protected StreamRecordSerializer<IN> inSerializer;
	protected TypeSerializer<IN> objectSerializer;
	protected StreamRecord<IN> nextRecord;

	protected FTContext ftContext;

	protected AbstractStreamCollector<OUT, ?> collector;
	protected Function userFunction;
	protected volatile boolean isRunning;

	public StreamInvokable(Function userFunction) {
		this.userFunction = userFunction;
	}

	/**
	 * Initializes the {@link StreamInvokable} for input and output handling
	 *
	 * @param taskContext StreamTaskContext representing the vertex
	 */
	public void setup(StreamTaskContext<OUT> taskContext, FTContext ftContext) {
		this.ftContext = ftContext;

		this.collector = taskContext.getOutputCollector();
		this.recordIterator = taskContext.getInput(0);
		this.inSerializer = taskContext.getInputSerializer(0);
		if (this.inSerializer != null) {
			this.nextRecord = inSerializer.createInstance();
			this.objectSerializer = inSerializer.getObjectSerializer();
		}
		this.taskContext = taskContext;
	}

	/**
	 * Method that will be called when the operator starts, should encode the
	 * processing logic
	 */
	public abstract void invoke() throws Exception;

	/*
	* Reads the next record from the reader iterator and stores it in the
	* nextRecord variable
	*/
	protected StreamRecord<IN> readNext() {
		this.nextRecord = inSerializer.createInstance();
		try {
			return nextRecord = recordIterator.next(nextRecord);
		} catch (IOException e) {
			throw new RuntimeException("Could not read next record.");
		}
	}

	/**
	 * The call of the user implemented function should be implemented here
	 */
	protected void callUserFunction() throws Exception {
	}

	/**
	 * Method for logging exceptions thrown during the user function call
	 */
	protected void callUserFunctionAndLogException() {
		try {
			setAnchorRecord();
			callUserFunction();
			ackAnchorRecord();
		} catch (FailException e) {
			ftContext.setFailFlag(true);
			ftContext.xor(nextRecord);
		} catch (Exception e) {
			if (LOG.isErrorEnabled()) {
				LOG.error("Calling user function failed due to: {}",
						StringUtils.stringifyException(e));
			}
		}
	}

	/**
	 * Open method to be used if the user defined function extends the
	 * RichFunction class
	 *
	 * @param parameters The configuration parameters for the operator
	 */
	public void open(Configuration parameters) throws Exception {
		isRunning = true;
		FunctionUtils.openFunction(userFunction, parameters);
	}

	/**
	 * Close method to be used if the user defined function extends the
	 * RichFunction class
	 */
	public void close() throws Exception {
		isRunning = false;
		collector.close();
		FunctionUtils.closeFunction(userFunction);
	}

	public void setRuntimeContext(RuntimeContext t) {
		FunctionUtils.setFunctionRuntimeContext(userFunction, t);
	}

	protected IN copy(IN record) {
		return objectSerializer.copy(record);
	}

	protected void ackAnchorRecord() {
		ftContext.xor(nextRecord);
	}

	protected void setAnchorRecord() {
		collector.setAnchorRecord(nextRecord.getId());
	}
}
