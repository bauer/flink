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
import com.twitter.chill.protobuf.ProtobufSerializer;
import io.github.bauer.ProtobufReport.Report;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer010;
import org.apache.flink.streaming.util.serialization.SerializationSchema;

import java.util.Properties;

/**
 * Generate a Report every 500 ms and write it into a Kafka topic specified by user
 */
public class KafkaWrite {

	public static void main(String[] args) throws Exception {
		ParameterTool parameterTool = ParameterTool.fromArgs(args);
		if(parameterTool.getNumberOfParameters() < 1) {
			System.out.println("Missing parameter!");
			System.out.println("Usage: bin/flink run -c io.github.bauer.flink.job.KafkaWrite ./../job1-0.1.jar --topic <topic>");
			return;
		}
		String topicKey = parameterTool.getRequired("topic");

		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setParallelism(1);
		env.getConfig().enableSysoutLogging();

		Properties kafkaConfig = new Properties();
		kafkaConfig.setProperty("bootstrap.servers", "localhost:9092");

		env.getConfig().registerTypeWithKryoSerializer(Report.class, ProtobufSerializer.class);
		env.getConfig().addDefaultKryoSerializer(Report.class, ProtobufSerializer.class);

		// data generator
		DataStream<Report> messageStream = env.addSource( new SourceFunction<Report>() {
			private static final long serialVersionUID = 20171L;
			public boolean running = true;

			@Override
			public void run(SourceContext<Report> ctx) throws Exception {
				Long Counter = Long.valueOf(0);
				while(this.running) {
					Counter++;
					Long ZeroToTwo = Counter%3;
					Long ZeroToNine = Counter%10;
					Report.Builder reportBuilder = Report.newBuilder();
					reportBuilder
							.setProduct("FakeProduct")
							.setSoftware("Software-" + ZeroToNine.toString())
							.setHardware("Hardware-" + ZeroToTwo.toString())
							.setValue(Counter.intValue());

					// build a report
					Report report = reportBuilder.build();
					// add report to generated stream
					ctx.collect(report);
					Thread.sleep(500);
				}
			}

			@Override
			public void cancel() {
				running = false;
			}
		}).name("Generate");

		// write data into Kafka
		messageStream.addSink(
			new FlinkKafkaProducer010<Report>(
				topicKey,
				new ReportSerializationSchema(),
				kafkaConfig
			)
		).name("KafkaSink");
		env.execute("Flink write Kafka topic " + topicKey);
	}

	/*
	 * The serialization schema describes how to turn a data object into a different serialized
	 * representation. Most data sinks (for example Apache Kafka) require the data to be handed
	 * to them in a specific format (for example as byte strings).
	 */
	private static class ReportSerializationSchema implements SerializationSchema<Report> {
		private static final long serialVersionUID = 20172L;

		@Override
		public byte[] serialize(Report report) {
			return report.toByteArray();
		}
	}
}
