package org.apache.sling.devops.orchestrator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.apache.sling.crankstart.api.CrankstartConstants;
import org.apache.sling.devops.Instance;
import org.apache.sling.devops.orchestrator.git.GitFileMonitor;
import org.apache.sling.devops.orchestrator.git.LocalGitFileMonitor;
import org.apache.sling.devops.orchestrator.git.RemoteGitFileMonitor;
import org.apache.sling.settings.SlingSettingsService;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;

@Component(immediate=true)
@Service
public class DefaultOrchestrator implements Orchestrator {

	private static final Logger logger = LoggerFactory.getLogger(DefaultOrchestrator.class);

	private static final SimpleDateFormat LOG_APPENDER_DATE_FORMAT = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss.SSS");

	public static final String DEVOPS_DIR = "devops";
	public static final String GIT_WORKING_COPY_DIR = DEVOPS_DIR + "/repo";

	/* Properties without default values */

	@Property(label = "Path to the monitored Git repository")
	public static final String GIT_REPO_PATH_PROP = "sling.devops.git.repo";

	@Property(label = "Path to the monitored file within the Git repository")
	public static final String GIT_REPO_FILE_PATH_PROP = "sling.devops.git.file";

	@Property(label = "ZooKeeper connection string")
	public static final String ZK_CONNECTION_STRING_PROP = "sling.devops.zookeeper.connString";

	@Property(label = "Password for sudo command")
	public static final String SUDO_PASSWORD_PROP = "sudo.password";

	/* Properties with default values */

	public static final int GIT_PERIOD_DEFAULT = 1;
	public static final String GIT_PERIOD_UNIT_DEFAULT = "MINUTES";
	public static final int N_DEFAULT = 2;

	@Property(label = "Period for Git repository polling", intValue = GIT_PERIOD_DEFAULT)
	public static final String GIT_PERIOD_PROP = "sling.devops.git.period";

	@Property(label = "Period unit for Git repository polling", value = GIT_PERIOD_UNIT_DEFAULT)
	public static final String GIT_PERIOD_UNIT_PROP = "sling.devops.git.period.unit";

	@Property(label = "N, the number of Minions running a config must be available before it is transitioned to", intValue = N_DEFAULT)
	public static final String N_PROP = "sling.devops.orchestrator.n";

	@Reference
	private SlingSettingsService slingSettingsService;
	
	@Reference
	private ConfigTransitioner configTransitioner;

	private File devopsDirectory;
	private int n;
	private InstanceMonitor instanceMonitor;
	private InstanceManager instanceManager;
	private GitFileMonitor gitFileMonitor;
	private MinionController minionController;
	private String activeConfig = "";
	private String targetConfig = "";
	private ListAppender<ILoggingEvent> logAppender = new ListAppender<>();

	@Activate
	public void onActivate(final ComponentContext componentContext) throws GitAPIException, IOException, InterruptedException {
		final BundleContext bundleContext = componentContext.getBundleContext();

		final Dictionary<?, ?> properties = componentContext.getProperties();
		this.n = PropertiesUtil.toInteger(properties.get(N_PROP), N_DEFAULT);
		this.instanceManager = new InstanceManager();

		// Create devops directory
		this.devopsDirectory = new File(this.slingSettingsService.getAbsolutePathWithinSlingHome(DEVOPS_DIR));
		if (!this.devopsDirectory.exists()) this.devopsDirectory.mkdir();

		// Setup minion controller
		final String crankstartJar = bundleContext.getProperty(CrankstartConstants.CRANKSTART_JAR_PATH);
		if (crankstartJar != null) this.minionController = new CrankstartMinionController(crankstartJar);
		else this.minionController = new ManualMinionController();

		// Setup instance listener
		this.instanceMonitor = new ZooKeeperInstanceMonitor(PropertiesUtil.toString(properties.get(ZK_CONNECTION_STRING_PROP), null));
		this.instanceMonitor.addInstanceListener(new InstanceMonitor.InstanceListener() {

			@Override
			public void onInstanceAdded(Instance instance) {
				DefaultOrchestrator.this.instanceManager.addInstance(instance);
				DefaultOrchestrator.this.tryTransition(instance.getConfig());
			}

			@Override
			public void onInstanceChanged(Instance instance) {
				this.onInstanceRemoved(instance.getId());
				this.onInstanceAdded(instance);
			}

			@Override
			public void onInstanceRemoved(String slingId) {
				DefaultOrchestrator.this.instanceManager.removeInstance(slingId);
			}
		});

		// Setup Git monitor
		final String gitRepoPath = PropertiesUtil.toString(properties.get(GIT_REPO_PATH_PROP), null);
		final String gitRepoFilePath = PropertiesUtil.toString(properties.get(GIT_REPO_FILE_PATH_PROP), null);
		final int gitRepoPeriod = PropertiesUtil.toInteger(properties.get(GIT_PERIOD_PROP), GIT_PERIOD_DEFAULT);
		final TimeUnit gitRepoTimeUnit = TimeUnit.valueOf(
				PropertiesUtil.toString(properties.get(GIT_PERIOD_UNIT_PROP), GIT_PERIOD_UNIT_DEFAULT));
		if (gitRepoPath.contains("://")) { // assume remote
			this.gitFileMonitor = new RemoteGitFileMonitor(
					gitRepoPath,
					this.slingSettingsService.getAbsolutePathWithinSlingHome(GIT_WORKING_COPY_DIR),
					gitRepoFilePath,
					gitRepoPeriod,
					gitRepoTimeUnit
					);
		} else {
			this.gitFileMonitor = new LocalGitFileMonitor(
					gitRepoPath,
					gitRepoFilePath,
					gitRepoPeriod,
					gitRepoTimeUnit
					);
		}
		this.gitFileMonitor.addListener(new GitFileMonitor.GitFileListener() {
			@Override
			public synchronized void onModified(long time, ByteBuffer content) {
				final String config = "C" + time / 1000;
				if (!DefaultOrchestrator.this.tryTransition(config)) {
					final File configFile = new File(DefaultOrchestrator.this.devopsDirectory, config + ".crank.txt");
					try (final FileChannel fileChannel = new FileOutputStream(configFile, false).getChannel()) {
						fileChannel.write(content);
					} catch (IOException e) {
						logger.error("Could not write crank.txt file.", e);
					}
					try {
						DefaultOrchestrator.this.minionController.startMinions(
								config,
								configFile.getAbsolutePath(),
								DefaultOrchestrator.this.n - DefaultOrchestrator.this.instanceManager.getEndpoints(config).size()
								);
					} catch (Exception e) {
						logger.error("Could not start Minions.", e);
					}
				}
			}
		});

		// Configure log appender
		final Dictionary<String, Object> logAppenderProperties = new Hashtable<>();
		logAppenderProperties.put("loggers", new String[]{
				this.getClass().getName(),
				this.instanceMonitor.getClass().getName(),
				this.gitFileMonitor.getClass().getSuperclass().getName(),
				this.minionController.getClass().getName(),
				this.configTransitioner.getClass().getName()
				});
		bundleContext.registerService(Appender.class.getName(), this.logAppender, logAppenderProperties);

		// Let's roll!
		this.gitFileMonitor.start();
	}

	@Deactivate
	public void onDeactivate() throws Exception {
		this.configTransitioner.close();
		this.minionController.close();
		this.gitFileMonitor.close();
		this.instanceMonitor.close();
	}

	@Override
	public int getN() {
		return this.n;
	}

	@Override
	public String getActiveConfig() {
		return this.activeConfig;
	}

	@Override
	public String getTargetConfig() {
		return this.targetConfig;
	}

	@Override
	public Map<String, Set<String>> getConfigs() {
		return this.instanceManager.getConfigs();
	}

	@Override
	public List<String> getLog() {
		final List<String> list = new LinkedList<>();
		for (final ILoggingEvent e : logAppender.list) list.add(String.format(
				"%s *%s* %s %s",
				LOG_APPENDER_DATE_FORMAT.format(new Date(e.getTimeStamp())),
				e.getLevel(),
				e.getLoggerName().substring(e.getLoggerName().lastIndexOf('.') + 1),
				e.getFormattedMessage()
				));
		return list;
	}

	/**
	 * Tries to transition to the specified config and returns whether the
	 * transition succeeded.
	 *
	 * A transition is considered successful if (i) the specified config
	 * is outdated, or (ii) the specified config is current (not outdated)
	 * and is satisfied. In the latter case, the minions running the config
	 * prior to the transition are afterwards stopped.
	 *
	 * @param newConfig config to try to transition to
	 * @return true if the transition occurred or the config is outdated, false otherwise
	 */
	private synchronized boolean tryTransition(final String newConfig) {
		if (this.isConfigOutdated(newConfig)) {
			logger.info("Config {} is outdated, ignored.", newConfig);
			return true;
		} else {
			this.targetConfig = newConfig;
			if (this.isConfigSatisfied(newConfig)) {
				logger.info("Config {} satisfied, transitioning...", newConfig);
				try {
					this.configTransitioner.transition(
							newConfig,
							this.instanceManager.getEndpoints(newConfig)
							);
					if (!newConfig.equals(this.getActiveConfig()) && !this.instanceManager.getEndpoints(this.getActiveConfig()).isEmpty()) {
						try {
							this.minionController.stopMinions(this.getActiveConfig());
						} catch (Exception e) {
							logger.error("Could not stop Minions.", e);
						}
					}
					this.activeConfig = newConfig;
					return true;
				} catch (Exception e) {
					// TODO Auto-generated catch block
					logger.error("Transition failed.", e);
				}
			}
		}
		return false;
	}

	private boolean isConfigOutdated(final String newConfig) {
		return newConfig.compareTo(this.getTargetConfig()) < 0;
	}

	private boolean isConfigSatisfied(final String newConfig) {
		return newConfig.equals(this.getTargetConfig())
				&& this.instanceManager.getEndpoints(newConfig).size() >= this.n;
	}
}
