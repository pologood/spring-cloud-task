/*
 * Copyright 2016 the original author or authors.
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
 */
package io.spring;

import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.explore.support.JobExplorerFactoryBean;
import org.springframework.batch.core.partition.PartitionHandler;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.deployer.resource.support.DelegatingResourceLoader;
import org.springframework.cloud.deployer.spi.local.LocalDeployerProperties;
import org.springframework.cloud.deployer.spi.local.LocalTaskLauncher;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.task.batch.partition.DeployerPartitionHandler;
import org.springframework.cloud.task.batch.partition.DeployerStepExecutionHandler;
import org.springframework.cloud.task.batch.partition.SimpleEnvironmentVariablesProvider;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;

/**
 * @author Michael Minella
 */
@Slf4j
@Configuration
public class JobConfiguration {

	public static final SimpleDateFormat sdf = new SimpleDateFormat("YYYYMMddHHmmss");

	@Autowired
	public JobBuilderFactory jobBuilderFactory;

	@Autowired
	public StepBuilderFactory stepBuilderFactory;

	@Autowired
	public JobRepository jobRepository;

	@Autowired
	private ConfigurableApplicationContext context;

	@Autowired
	private DelegatingResourceLoader resourceLoader;

	@Autowired
	private Environment environment;

	private static final int GRID_SIZE = 4;

	// TODO: will be loaded in worker profile
	@Bean
	public JobExplorerFactoryBean jobExplorer(DataSource source) {
		log.info("{}", new Object(){}.getClass().getEnclosingMethod());

		JobExplorerFactoryBean jobExplorerFactoryBean = new JobExplorerFactoryBean();

		jobExplorerFactoryBean.setDataSource(source);

		return jobExplorerFactoryBean;
	}

	@Bean
	public TaskLauncher taskLauncher() {
		log.info("{}", new Object(){}.getClass().getEnclosingMethod());
		LocalDeployerProperties localDeployerProperties = new LocalDeployerProperties();

		localDeployerProperties.setDeleteFilesOnExit(false);

		return new LocalTaskLauncher(localDeployerProperties);
	}

	@Bean
	public PartitionHandler partitionHandler(TaskLauncher taskLauncher, JobExplorer jobExplorer) throws Exception {
		log.info("{}", new Object(){}.getClass().getEnclosingMethod());
		Resource resource = resourceLoader.getResource("maven://io.spring.cloud:partitioned-batch-job:1.0.3.RELEASE");

		DeployerPartitionHandler partitionHandler = new DeployerPartitionHandler(taskLauncher, jobExplorer, resource, "workerStep");

		Map<String, String> environmentProperties = new HashMap<>();
		environmentProperties.put("spring.profiles.active", "worker");

		SimpleEnvironmentVariablesProvider environmentVariablesProvider = new SimpleEnvironmentVariablesProvider(this.environment);
		environmentVariablesProvider.setEnvironmentProperties(environmentProperties);
		partitionHandler.setEnvironmentVariablesProvider(environmentVariablesProvider);

		partitionHandler.setMaxWorkers(2);

		return partitionHandler;
	}

	@Bean
	public Partitioner partitioner() {
		log.info("{}", new Object(){}.getClass().getEnclosingMethod());
		return new Partitioner() {
			@Override
			public Map<String, ExecutionContext> partition(int gridSize) {

				Map<String, ExecutionContext> partitions = new HashMap<>(gridSize);

				for(int i = 0; i < GRID_SIZE; i++) {
					ExecutionContext context1 = new ExecutionContext();
					context1.put("partitionNumber", i);

					partitions.put("partition" + i, context1);
				}

				return partitions;
			}
		};
	}

	// TODO: will be loaded in worker profile
	@Bean
	@Profile("worker")
	public DeployerStepExecutionHandler stepExecutionHandler(JobExplorer jobExplorer) {
		log.info("{}", new Object(){}.getClass().getEnclosingMethod());
		return new DeployerStepExecutionHandler(this.context, jobExplorer, this.jobRepository);
	}

	// TODO: @Value should be different in different profiles.
	@Bean
	@StepScope
	public Tasklet workerTasklet(
			final @Value("#{stepExecutionContext['partitionNumber']}")Integer partitionNumber) {

		log.info("{}", new Object(){}.getClass().getEnclosingMethod());
		return new Tasklet() {
			@Override
			public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
				System.out.println("This tasklet ran partition: " + partitionNumber);
				FileOutputStream fos = new FileOutputStream("d:\\" + partitionNumber + "_" + sdf.format(new Date()) + ".txt",
					false);
				fos.write(("This tasklet ran partition: " + partitionNumber).getBytes());
				fos.flush();
				fos.close();
				return RepeatStatus.FINISHED;
			}
		};
	}

	@Bean
	public Step step1(PartitionHandler partitionHandler) throws Exception {
		log.info("{}", new Object(){}.getClass().getEnclosingMethod());
		return stepBuilderFactory.get("step1")
				.partitioner(workerStep().getName(), partitioner())
				.step(workerStep())
				.partitionHandler(partitionHandler)
				.build();
	}

	@Bean
	public Step workerStep() {
		log.info("{}", new Object(){}.getClass().getEnclosingMethod());
		return stepBuilderFactory.get("workerStep")
				.tasklet(workerTasklet(null))
				.build();
	}

	// TODO: will only be valid in master profile
	@Bean
	@Profile("master")
	public Job partitionedJob(PartitionHandler partitionHandler) throws Exception {
		log.info("{}", new Object(){}.getClass().getEnclosingMethod());
		return jobBuilderFactory.get("partitionedJob")
				.start(step1(partitionHandler))
				.build();
	}
}
