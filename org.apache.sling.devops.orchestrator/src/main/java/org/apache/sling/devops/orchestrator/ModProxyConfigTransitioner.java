package org.apache.sling.devops.orchestrator;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.LogOutputStream;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@Service
public class ModProxyConfigTransitioner implements ConfigTransitioner {

	private static final Logger logger = LoggerFactory.getLogger(ModProxyConfigTransitioner.class);

    @Property(label = "Command to execute to activate a new load balancer config")
    public static final String HTTPD = "sling.devops.modproxy.httpd";

    @Property(label = "Path to mod_proxy_balancer config file")
    public static final String HTTPD_BALANCER_CONFIG_PATH_PROP = "sling.devops.httpd.balancer.config";

	private CommandLine httpdCommand;
	private File httpdConfigFile;

	@Activate
	protected void onActivate(ComponentContext ctx) {
	    httpdCommand = CommandLine.parse(
	            PropertiesUtil.toString(ctx.getProperties().get(HTTPD), "MISSING_" + HTTPD));
	    httpdConfigFile = new File(PropertiesUtil.toString(ctx.getProperties().get(HTTPD_BALANCER_CONFIG_PATH_PROP), 
	            "MISSING_" + HTTPD_BALANCER_CONFIG_PATH_PROP));
	    logger.info("Activated, with command line {} and httpd config file {}", 
	            httpdCommand.toString(),
	            httpdConfigFile.getAbsolutePath());
	    
	    try {
	        // TODO activate once crankstart supports untranslated ${command}
	        // this.httpd("start");
	    } catch(Exception e) {
	        logger.warn("httpd execution failed", e);
	    }
	}
	
 	@Override
	public void transition(String config, Set<String> endpoints) throws IOException, InterruptedException {

		// Update config file
		try (PrintWriter writer = new PrintWriter(httpdConfigFile)) {
		    writer.print("# written by ");
		    writer.print(getClass().getSimpleName());
		    writer.print(" for config ");
		    writer.print(config);
		    writer.print(" at ");
		    writer.println(new Date());
		    
			for (String endpoint : endpoints) {
				writer.println(String.format(
						"BalancerMember %s route=%s",
						endpoint.charAt(endpoint.length() - 1) == '/' ? // mod_proxy_balancer complains about trailing slashes
								endpoint.substring(0, endpoint.length() - 1) :
									endpoint,
						endpoint.hashCode()
						));
			}
		}
		logger.info("Proxy config {} rewritten for endpoints {}", httpdConfigFile.getAbsolutePath(), endpoints);
		httpd("graceful");
	}

	@Override
	public void close() throws IOException {
        // TODO activate once crankstart supports untranslated ${command}
		// httpd("stop");
	}

	private void httpd(String command) {

	    // Replace ${command} in configured command line
	    final CommandLine toExecute = new CommandLine(httpdCommand);
	    final Map<String, Object> params = new HashMap<>();
        params.put("command", command);
        toExecute.setSubstitutionMap(params);
	    
		final Executor executor = new DefaultExecutor();
		final List<String> errors = new LinkedList<>();
		executor.setStreamHandler(new PumpStreamHandler(
				new LogOutputStream() { // stdout: log as INFO
					@Override
					protected void processLine(final String line, final int level) {
						logger.info(line);
					}
				},
				new LogOutputStream() { // stderr: remember
					@Override
					protected void processLine(final String line, final int level) {
						errors.add(line);
					}
				},
				new ByteArrayInputStream(new byte[]{})  // stdin: nothing
		));
		
		try {
	        final int exitValue = executor.execute(toExecute);
	        
	        // Log errors: ERROR level if exit code not 0, WARN level otherwise
	        if (exitValue != 0) {
	            for (final String error : errors) logger.error(error);
	            logger.error("Proxy command \"{}\" exited with value {}.", toExecute, exitValue);
	        } else {
	            for (final String error : errors) logger.warn(error);
	            logger.info("Proxy command \"{}\" succeeded.", toExecute);
	        }
		} catch(IOException ioe) {
		    logger.error("Command execution failed :" + toExecute, ioe);
		}

	}
}