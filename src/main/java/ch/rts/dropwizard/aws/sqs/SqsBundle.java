package ch.rts.dropwizard.aws.sqs;

import ch.rts.dropwizard.aws.sqs.config.SqsConfigurationHolder;
import ch.rts.dropwizard.aws.sqs.exception.CannotCreateSenderException;
import ch.rts.dropwizard.aws.sqs.exception.SqsBaseExceptionHandler;
import ch.rts.dropwizard.aws.sqs.health.SqsBundleHealthCheck;
import ch.rts.dropwizard.aws.sqs.managed.SqsReceiver;
import ch.rts.dropwizard.aws.sqs.managed.SqsReceiverHandler;
import ch.rts.dropwizard.aws.sqs.service.SqsSender;
import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.GetQueueUrlResult;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.QueueDoesNotExistException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class SqsBundle implements ConfiguredBundle<SqsConfigurationHolder>, Managed {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqsBundle.class);

    private SqsConfigurationHolder configuration;
    private Environment environment;

    private AmazonSQS sqs;

    private ObjectMapper objectMapper;

    public SqsBundle() {
    }

    @Override
    public void run(SqsConfigurationHolder configuration, Environment environment) throws Exception {
        this.configuration = configuration;
        this.environment = environment;

        objectMapper = environment.getObjectMapper();

        sqs = getAmazonSQS();

        setSqsRegion();

        environment.lifecycle().manage(this);
        environment.healthChecks().register("SqsBundle", new SqsBundleHealthCheck(sqs));
    }

    public SqsSender createSender(String queueName) throws CannotCreateSenderException {
        Optional<String> queueUrl = getUrlForQueue(queueName);
        if (queueUrl.isPresent()) {
            return new SqsSender(sqs, queueUrl.get(), objectMapper);
        } else {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Could not create sender for queue name " + queueName + ", no messages will be sent for this queue");
            }
            throw new CannotCreateSenderException("Could not create sender for queue name " + queueName + ", no messages will be sent for this queue");
        }
    }

    public <T> void registerReceiver(String queueName, SqsReceiver<T> receiver) {
        Optional<String> queueUrl = getUrlForQueue(queueName);
        if (queueUrl.isPresent()) {
            SqsReceiverHandler<T> handler = new SqsReceiverHandler<>(
                    sqs,
                    queueUrl.get(),
                    receiver,
                    new SqsBaseExceptionHandler() { // not replaced with lambda because jacoco fails with lambdas
                        @Override
                        public boolean onException(Message message, Exception exception) {
                            LOGGER.error("Error processing received message - acknowledging it anyway");
                            return true;
                        }
                    }
            );
            internalRegisterReceiver(queueName, handler);
        }
        else {
            LOGGER.error("Cannot register receiver for queue name : " + queueName);
        }
    }

    public <T> void registerReceiver(String queueName, SqsReceiver<T> receiver, SqsBaseExceptionHandler exceptionHandler) {
        Optional<String> queueUrl = getUrlForQueue(queueName);
        if (queueUrl.isPresent()) {
            SqsReceiverHandler<T> handler = new SqsReceiverHandler<>(
                    sqs,
                    queueUrl.get(),
                    receiver,
                    exceptionHandler
            );
            internalRegisterReceiver(queueName, handler);
        }
        else {
            LOGGER.error("Cannot register receiver for queue name : " + queueName);
        }
    }

    <T> void internalRegisterReceiver(String queueName, SqsReceiverHandler<T> handler) {
        environment.lifecycle().manage(handler);
        environment.healthChecks().register("SQS receiver for " + queueName, handler.getHealthCheck());
    }

    AmazonSQS getAmazonSQS() {
        AWSCredentials credentials = getAwsCredentials();

        return new AmazonSQSClient(credentials);
    }

    AWSCredentials getAwsCredentials() {
        // The ProfileCredentialsProvider will return your [default]
        // credential profile by reading from the credentials file located at
        // (~/.aws/credentials).
        AWSCredentials credentials;
        try {
            credentials = new ProfileCredentialsProvider().getCredentials();
        } catch (Exception e) {
            throw new AmazonClientException(
                    "Cannot load the credentials from the credential profiles file. " +
                            "Please make sure that your credentials file is at the correct " +
                            "location (~/.aws/credentials), and is in valid format.",
                    e);
        }
        return credentials;
    }

    void setSqsRegion() {
        String regionName = this.configuration.getSqsConfiguration().getRegion();
        Region region = RegionUtils.getRegion(regionName);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Setting SQS region to " + region.getName());
        }
        sqs.setRegion(region);
    }

    /**
     * Retrieves queue url for the given queue name. If the queue does not exist, tries to create it.
     *
     * @param queueName the queue name to get url for
     * @return an optional String representing the queue url
     */
    Optional<String> getUrlForQueue(String queueName) {
        Optional<String> queueUrl = Optional.empty();
        try {
            GetQueueUrlResult queueUrlResult = sqs.getQueueUrl(queueName);
            if (queueUrlResult.getQueueUrl() != null) {
                queueUrl = Optional.of(queueUrlResult.getQueueUrl());
            }
        } catch (QueueDoesNotExistException e) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Queue " + queueName + " does not exist, try to create it",e);
            }
            CreateQueueRequest createQueueRequest = new CreateQueueRequest(queueName);
            try {
                queueUrl = Optional.of(sqs.createQueue(createQueueRequest).getQueueUrl());
            } catch (AmazonClientException e2) {
                LOGGER.error("Could not create queue " + queueName + ", bundle won't work",e2);
            }
        }

        return queueUrl;
    }

    @Override
    public void initialize(Bootstrap<?> bootstrap) {
        // mandatory override not used here
    }

    @Override
    public void start() throws Exception {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Starting SQS client");
        }
    }

    @Override
    public void stop() throws Exception {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Stopping SQS client");
        }
    }

}