package io.github.bauer.flink.job;
/*******************************************************************************
 * Copyright (C) 2017 Bauer <bauer.github@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
import com.google.protobuf.InvalidProtocolBufferException;
import com.twitter.chill.protobuf.ProtobufSerializer;
import io.github.bauer.ProtobufReport.Report;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.TypeExtractor;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer010;
import org.apache.flink.streaming.util.serialization.DeserializationSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Read Report messages from Kafka topic specified by user and print them
 */
public class KafkaRead {

	private static final Logger LOG = LoggerFactory.getLogger(io.github.bauer.flink.job.KafkaRead.class);

	public static void main(String[] args) throws Exception {
		// parse input arguments
		final ParameterTool parameterTool = ParameterTool.fromArgs(args);
		if(parameterTool.getNumberOfParameters() < 1) {
			System.out.println("Missing parameter!");
			System.out.println("Usage: bin/flink run -c io.github.bauer.flink.job.KafkaRead ./../job1-0.1.jar --topic <topic>");
			return;
		}
		String topicKey = parameterTool.getRequired("topic");

		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setParallelism(1);
		env.getConfig().enableSysoutLogging();

		env.getConfig().registerTypeWithKryoSerializer(Report.class, ProtobufSerializer.class);
		env.getConfig().addDefaultKryoSerializer(Report.class, ProtobufSerializer.class);

		Properties kafkaConfig = new Properties();
		kafkaConfig.setProperty("bootstrap.servers", "localhost:9092");
		kafkaConfig.setProperty("group.id", "Flink-KafkaRead");

		DataStream<Report> messageStream = env
				.addSource(
					new FlinkKafkaConsumer010<>(
						topicKey,
						new ReportDeserializationSchema(),
							kafkaConfig
					)
				).name("KafkaSource");

		messageStream
				// this filter is required when deserilizer is allowed to return null
				.filter(new FilterFunction<Report>() {
					@Override
					public boolean filter(Report value) throws Exception {
						return value != null;
					}
				})
				.print().name("PrintSink");

		env.execute("Flink read Kafka topic " + topicKey);
	}

	/*
	 * The deserialization schema describes how to turn the byte messages delivered by certain
	 * data sources (for example Apache Kafka) into data types (Java/Scala objects) that are
	 * processed by Flink.
	 */
	private static class ReportDeserializationSchema implements DeserializationSchema<Report> {
		private static final long serialVersionUID = 1L;

		@Override
		public Report deserialize(byte[] bytes) {
			try {
				return Report.parseFrom(bytes);
			} catch (final InvalidProtocolBufferException e) {
				/*
				 * When encountering a corrupted message that cannot be deserialized for any reason,
				 * there are two options - either throwing an exception from the deserialize(...) method which will
				 * cause the job to fail and be restarted, or returning null to allow the Flink Kafka consumer to
				 * silently skip the corrupted message. Note that due to the consumer’s fault tolerance,
				 * failing the job on the corrupted message will let the consumer attempt to deserialize the message
				 * again. Therefore, if deserialization still fails, the consumer will fall into a non-stop restart
				 * and fail loop on that corrupted message.
				 */
				LOG.error("Received unparseable message", e);
				//throw new RuntimeException("Received unparseable message " + e.getMessage(), e);
				return null;
			}
		}

		@Override
		public boolean isEndOfStream(Report nextElement) {
			return false;
		}

		@Override
		public TypeInformation<Report> getProducedType() {
			return TypeExtractor.getForClass(Report.class);
		}
	}
}
