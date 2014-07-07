package org.apache.sling.devops.orchestrator;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteResultHandler;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.LogOutputStream;
import org.apache.commons.exec.PumpStreamHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CrankstartMinionController implements MinionController {

	private static final Logger logger = LoggerFactory.getLogger(CrankstartMinionController.class);

	public static final int PORT_MIN = 1024; // exclude "system ports" 1-1023
	public static final int PORT_MAX = 65535;

	private final CommandLine baseCommandLine;
	private final Map<String, List<Executor>> configInstances;
	private final Map<Executor, Integer> instancePorts;
	private final Map<Executor, String> instanceHomes;
	private final Random random;

	public CrankstartMinionController(final String crankstartJar) {
		if (!new File(crankstartJar).exists()) {
			throw new IllegalArgumentException(String.format("%s does not exist.", crankstartJar));
		}
		this.baseCommandLine = new CommandLine("java");
		this.baseCommandLine.addArgument("-Dport=${port}");
		this.baseCommandLine.addArgument("-Dconfig=${config}");
		this.baseCommandLine.addArgument("-Dsling_home=${sling_home}");
		this.baseCommandLine.addArgument("-jar");
		this.baseCommandLine.addArgument(crankstartJar);
		this.baseCommandLine.addArgument("${crankfile}");
		this.configInstances = new HashMap<>();
		this.instancePorts = new HashMap<>();
		this.instanceHomes = new HashMap<>();
		this.random = new Random();
	}

	@Override
	public void startMinions(final String config, final String crankFilePath, final int num) throws IOException {
		for (int i = 0; i < num; i++) {

			// Find available port
			int port;
			do {
				port = this.random.nextInt(PORT_MAX - PORT_MIN + 1) + PORT_MIN;
			} while (!isPortAvailable(port));

			final CommandLine commandLine = new CommandLine(this.baseCommandLine);

			// Prepare params
			final String home = String.format("sling-%s-%s-crankstart", config, port);
			final Map<String, Object> params = new HashMap<>();
			params.put("config", config);
			params.put("port", port);
			params.put("sling_home", home);
			params.put("crankfile", crankFilePath);
			commandLine.setSubstitutionMap(params);

			// Crankstart!
			final Executor executor = new DefaultExecutor();
			executor.setWatchdog(new ExecuteWatchdog(ExecuteWatchdog.INFINITE_TIMEOUT));
			executor.setStreamHandler(new PumpStreamHandler(new LogOutputStream() {
						@Override
						protected void processLine(final String line, final int level) {
							logger.debug(line);
						}
					}));
			logger.info("Starting a Minion with config={} on port {}", config, port);
			
			final ExecuteResultHandler eh = new DefaultExecuteResultHandler() {
			    
			    private String errorInfo() {
			        return "config=" + config + ", command line=" + commandLine + ", debug log might provide more info";
			    }
			    
                @Override
                public void onProcessComplete(int exitValue) {
                    super.onProcessComplete(exitValue);
                    if(exitValue == 0) {
                        logger.info("Minion process for config {} exited without errors", config);
                    } else {
                        logger.warn("Minion process execution failed (exit code={}), {}", exitValue, errorInfo());
                    }
                }

                @Override
                public void onProcessFailed(ExecuteException e) {
                    super.onProcessFailed(e);
                    logger.warn("Minion process execution failed, " + errorInfo(), e);
                }
			    
			};
			executor.execute(commandLine, eh);

			if (!this.configInstances.containsKey(config)) {
				this.configInstances.put(config, new LinkedList<Executor>());
			}
			this.configInstances.get(config).add(executor);
			this.instancePorts.put(executor, port);
			this.instanceHomes.put(executor, home);
		}
	}

	@Override
	public void stopMinions(final String config) {
		if (this.configInstances.containsKey(config)) {
			for (final Executor executor : this.configInstances.remove(config)) {
				logger.info(
						"Stopping a Minion with config={} on port {}...",
						config,
						this.instancePorts.remove(executor)
						);
				executor.getWatchdog().destroyProcess();
				Utils.delete(this.instanceHomes.remove(executor));
			}
		} else logger.warn("Not stopping Minions with config={}, none were started.", config);
	}

	@Override
	public void close() {
		for (final String config : this.configInstances.keySet()) {
			this.stopMinions(config);
		}
	}

	private static boolean isPortAvailable(final int port) {
	    try (final ServerSocket socket = new ServerSocket(port)) {
	        return true;
	    } catch (final Exception e) {
	        return false;
	    }
	}
}
