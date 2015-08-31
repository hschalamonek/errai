package org.jboss.errai.cdi.server.as;

import java.io.File;
import java.io.IOException;
import org.jboss.as.cli.CliInitializationException;
import org.jboss.as.cli.CommandContext;
import org.jboss.as.cli.CommandContextFactory;
import org.jboss.as.cli.CommandLineException;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.errai.cdi.server.gwt.util.StackTreeLogger;
import com.google.gwt.core.ext.ServletContainer;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.TreeLogger.Type;
import com.google.gwt.core.ext.UnableToCompleteException;

/**
 * Acts as a an adaptor between gwt's ServletContainer interface and a JBoss
 * AS/WildFly instance.
 *
 * @author Max Barkley <mbarkley@redhat.com>
 * @author Christian Sadilek <csadilek@redhat.com>
 */
public class JBossServletContainerAdaptor extends ServletContainer {

    private final CommandContext ctx;

    private final int port;
    private final StackTreeLogger logger;
    private final String context;
    @SuppressWarnings("unused")
    private final Process jbossProcess;

    private static final String NATIVE_CONTROLLER_PATH = "remote://localhost:9999";
    private static final String HTTP_CONTROLLER_PATH = "http-remoting://localhost:9990";
    private static final int MAX_RETRIES = 9;

    /**
     * Initialize the command context for a remote JBoss AS instance.
     *
     * @param port
     *            The port to which the JBoss instance binds.
     * @param appRootDir
     *            The exploded war directory to be deployed.
     * @param context
     *            The deployment context for the app.
     * @param treeLogger
     *            For logging events from this container.
     * @throws UnableToCompleteException
     *             Thrown if this container cannot properly connect or deploy.
     */
    public JBossServletContainerAdaptor(int port, File appRootDir, String context, TreeLogger treeLogger,
            Process jbossProcess) throws UnableToCompleteException {
        this.port = port;
        this.logger = new StackTreeLogger(treeLogger);
        this.jbossProcess = jbossProcess;
        this.context = context;

        this.logger.branch(Type.INFO, "Starting container initialization...");

        CommandContext ctx = null;
        try {
            // Create command context
            try {

                this.logger.branch(Type.INFO, "Creating new command context...");
                ctx = CommandContextFactory.getInstance().newCommandContext();
                this.ctx = ctx;

                this.logger.log(Type.INFO, "Command context created");
                this.logger.unbranch();
            } catch (CliInitializationException e) {
                this.logger.branch(TreeLogger.Type.ERROR, "Could not initialize JBoss AS command context", e);
                throw new UnableToCompleteException();
            }

            attemptCommandContextConnection(MAX_RETRIES);

            int i;
            for (i = 0; i < MAX_RETRIES; i++) {
                try {
                    // Undeploy the app in case the container/devmode wasn't shutdown correctly which should
                    // have removed the deployment (see stop method).
                    removeDeployment();

                    /*
                     * Need to add deployment resource to specify exploded archive
                     * path : the absolute path the deployment file/directory archive : true
                     * iff the an archived file, false iff an exploded archive enabled :
                     * true iff war should be automatically scanned and deployed
                     */
                    this.logger.branch(Type.INFO,
                            String.format("Adding deployment %s at %s...", getAppName(), appRootDir.getAbsolutePath()));

                    final ModelNode operation = getAddOperation(appRootDir.getAbsolutePath());
                    final ModelNode result = ctx.getModelControllerClient().execute(operation);
                    if (!Operations.isSuccessfulOutcome(result)) {
                        this.logger.log(Type.ERROR, String.format("Could not add deployment:\nInput:\n%s\nOutput:\n%s",
                                operation.toJSONString(false), result.toJSONString(false)));
                        throw new IOException();
                    }

                    this.logger.log(Type.INFO, "Deployment resource added");
                    this.logger.unbranch();
                    break;
                } catch (IOException e) {
                    this.logger.log(Type.INFO, "Retrying ...");
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e1) {
                    }
                }
            }

            if (i == MAX_RETRIES) {
                this.logger.branch(Type.ERROR, String.format("Could not add deployment %s, giving up", getAppName()));
                throw new UnableToCompleteException();
            }

            attemptDeploy();

        } catch (UnableToCompleteException e) {
            this.logger.branch(Type.INFO, "Attempting to stop container...");
            stopHelper();

            throw e;
        }

    }

    @Override
    public int getPort() {
        return this.port;
    }

    @Override
    public void refresh() throws UnableToCompleteException {
        attemptDeploymentRelatedOp(ClientConstants.DEPLOYMENT_REDEPLOY_OPERATION);
    }

    @Override
    public void stop() throws UnableToCompleteException {
        try {
            this.logger.branch(Type.INFO, String.format("Removing %s from deployments...", getAppName()));

            ModelNode result = removeDeployment();
            if (!Operations.isSuccessfulOutcome(result)) {
                this.logger.log(
                        Type.ERROR,
                        String.format("Could not undeploy AS:\nInput:\n%s\nOutput:\n%s", getAppName(),
                                result.toJSONString(false)));
                throw new UnableToCompleteException();
            }

            this.logger.log(Type.INFO, String.format("%s removed", getAppName()));
            this.logger.unbranch();
        } catch (IOException e) {
            this.logger.log(Type.ERROR, "Could not shutdown AS", e);
            throw new UnableToCompleteException();
        } finally {
            stopHelper();
        }
    }

    private ModelNode removeDeployment() throws IOException {
        final ModelNode operation = Operations.createRemoveOperation(
                new ModelNode().add(ClientConstants.DEPLOYMENT, getAppName()));
        return this.ctx.getModelControllerClient().execute(operation);
    }

    private void attemptCommandContextConnection(final int maxRetries)
            throws UnableToCompleteException {

        final String[] controllers = new String[] {
                HTTP_CONTROLLER_PATH,
                NATIVE_CONTROLLER_PATH
        };
        final String[] protocols = new String[controllers.length];
        for (int i = 0; i < controllers.length; i++) {
            protocols[i] = controllers[i].split(":", 2)[0];
        }

        for (int retry = 0; retry < maxRetries; retry++) {
            for (int i = 0; i < controllers.length; i++) {
                final String controller = controllers[i];
                final String protocol = protocols[i];
                try {
                    this.logger.branch(Type.INFO, String.format("Attempting to connect with %s protocol.", protocol));
                    this.ctx.connectController(controller);
                    this.logger.log(Type.INFO, "Connected to JBoss AS");

                    return;
                } catch (CommandLineException e) {
                    this.logger.log(
                            Type.INFO,
                            String.format("Attempt %d failed at connecting with %s protocol", retry + 1, protocol),
                            e);
                } finally {
                    this.logger.unbranch();
                }
            }

            // No connection attempts have succeeded, so wait a bit before trying
            // again.
            if (retry < maxRetries) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e1) {
                    this.logger.log(Type.WARN, "Thread was interrupted while waiting for AS to reload", e1);
                }
            }
        }

        this.logger.log(Type.ERROR, "Could not connect to AS");
        throw new UnableToCompleteException();
    }

    private void stopHelper() {
        this.logger.branch(Type.INFO, "Attempting to stop JBoss AS instance...");
        /*
         * There is a problem with Process#destroy where it will not reliably kill
         * the JBoss instance. So instead we must try and send a shutdown signal. If
         * that is not possible or does not work, we will log it's failure, advising
         * the user to manually kill this process.
         */
        try {
            if (this.ctx.getControllerHost() == null) {
                this.ctx.handle("connect localhost:9999");
            }
            this.ctx.handle(":shutdown");

            this.logger.log(Type.INFO, "JBoss AS instance stopped");
            this.logger.unbranch();
        } catch (CommandLineException e) {
            this.logger.log(Type.ERROR, "Could not shutdown JBoss AS instance. "
                    + "Restarting this container while a JBoss AS instance is still running will cause errors.");
        }

        this.logger.branch(Type.INFO, "Terminating command context...");
        this.ctx.terminateSession();
        this.logger.log(Type.INFO, "Command context terminated");
        this.logger.unbranch();
    }

    private void attemptDeploy() throws UnableToCompleteException {
        attemptDeploymentRelatedOp(ClientConstants.DEPLOYMENT_DEPLOY_OPERATION);
    }

    private void attemptDeploymentRelatedOp(final String opName) throws UnableToCompleteException {
        try {
            this.logger.branch(Type.INFO, String.format("Deploying %s...", getAppName()));

            final ModelNode operation = Operations.createOperation(opName,
                    new ModelNode().add(ClientConstants.DEPLOYMENT, getAppName()));
            final ModelNode result = this.ctx.getModelControllerClient().execute(operation);

            if (!Operations.isSuccessfulOutcome(result)) {
                this.logger.log(
                        Type.ERROR,
                        String.format("Could not %s %s:\nInput:\n%s\nOutput:\n%s", opName, getAppName(),
                                operation.toJSONString(false), result.toJSONString(false)));
                throw new UnableToCompleteException();
            }

            this.logger.log(Type.INFO, String.format("%s %sed", getAppName(), opName));
            this.logger.unbranch();
        } catch (IOException e) {
            this.logger.branch(Type.ERROR, String.format("Could not %s %s", opName, getAppName()), e);
            throw new UnableToCompleteException();
        }
    }

    /**
     * @return The runtime-name for the given deployment.
     */
    private String getAppName() {
        // Deployment names must end with .war
        return this.context.endsWith(".war") ? this.context : this.context + ".war";
    }

    private ModelNode getAddOperation(String path) {
        final ModelNode command = Operations.createAddOperation(new ModelNode().add(ClientConstants.DEPLOYMENT,
                getAppName()));
        final ModelNode content = new ModelNode();
        final ModelNode contentObj = new ModelNode();

        // Construct content list
        contentObj.get("path").set(path);
        contentObj.get("archive").set(false);
        content.add(contentObj);

        command.get("content").set(content);
        command.get("enabled").set(false);

        return command;
    }

}
