package org.apache.sling.devops.orchestrator;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.devops.Instance;
import org.apache.sling.devops.zookeeper.ZooKeeperConnector;
import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ZooKeeperInstanceListener implements InstanceListener {

	private static final Logger logger = LoggerFactory.getLogger(ZooKeeperInstanceListener.class);

	public static final String ZK_CONNECTION_STRING_PROP = ZooKeeperConnector.ZK_CONNECTION_STRING_PROP;

	private ZooKeeperConnector zkConnector;
	private final Map<String, Instance> currentInstances = new HashMap<>();
	private int currentVersion = -1;

	public ZooKeeperInstanceListener(String connectionString) throws IOException {
		this.zkConnector = new ZooKeeperConnector(connectionString, new Watcher() {
			@Override
			public void process(WatchedEvent event) {
				if (event.getType() == Event.EventType.NodeChildrenChanged) {

					// Our watch fired: we should update the children, which must
					// re-set the watch.
					//
					// NOTE: the watch is not fired when the data of a child changes.
					// Therefore a child wishing to change its data must delete
					// and recreate its node.

					logger.info("Children changed, updating instances.");
					ZooKeeperInstanceListener.this.updateInstances();
				} else if (event.getType() != Event.EventType.None) {
					// our root node changed, shouldn't happen
					logger.warn("Node was changed, closing ZooKeeper connection.");
					logger.warn(event.toString() + " " + event.getType());
					ZooKeeperInstanceListener.this.zkConnector.close();
				}
			}
		});
		this.updateInstances();
	}

	@Override
	public void close() {
		this.zkConnector.close();
	}

	/**
	 * Updates the information we know about instances.
	 *
	 * This works by first retrieving the current children of our node, which
	 * also gives us the "children version" of the node. Then for each child
	 * we retrieve its data, and once we have the data of all children, we try
	 * to update our current information by invoking {@link #updateInstances(Set, int)}.
	 *
	 * Note that the children may have changed by the time we get all their data.
	 * This is okay because in that case the watch must have been fired, meaning
	 * this method was set to be called again.
	 */
	private void updateInstances() {
		this.zkConnector.getZooKeeper().getChildren(
				"/",
				true, // set our one-time watch, which must be set again when fired
				new AsyncCallback.Children2Callback() {
					@Override
					public void processResult(int code, String path, Object ctx, List<String> children, Stat stat) {
						if (Code.get(code) != Code.OK) return; // something is wrong
						final Set<Instance> instances = new HashSet<>();
						final int numChildren = children.size();
						final int version = stat.getCversion(); // children version
						if (numChildren > 0) {
							for (final String child : children) { // retrieve data of each child
								ZooKeeperInstanceListener.this.zkConnector.getZooKeeper().getData(
										"/" + child,
										false, // do not watch the children!
										new AsyncCallback.DataCallback() {
											@Override
											public void processResult(int code, String path, Object ctx, byte[] data, Stat stat) {
												if (Code.get(code) != Code.OK) return; // something is wrong
												String[] dataParts = new String(data).split(";");
												synchronized (instances) { // serial access so that only one callback sees the set complete
													instances.add(new Instance(
															child,
															dataParts[0].split("=")[1], // config=blah
															new HashSet<>(Arrays.asList(
																	dataParts[1].split("=")[1].split(",") // endpoints=ep1,ep2,ep3
																	))
															));

													// instance info for this version of children is ready? update
													if (instances.size() == numChildren) {
														ZooKeeperInstanceListener.this.updateInstances(instances, version);
													}
												}
											}
										},
										null
										);
							}
						} else ZooKeeperInstanceListener.this.updateInstances(instances, version);
					}
				},
				null
				);
	}

	/**
	 * Updates the information we know about instances with new information,
	 * respecting the increasing nature of instance versions.
	 *
	 * @param instances instances to update with
	 * @param version version of instances
	 */
	private synchronized void updateInstances(Set<Instance> instances, int version) {
		if (version > this.currentVersion) {
			Set<String> newIds = new HashSet<>();

			// detect new or changed instances
			for (Instance instance : instances) {
				String id = instance.getId();
				newIds.add(id);
				if (!this.currentInstances.containsKey(id)) {
					this.currentInstances.put(id, instance);
					this.onInstanceAdded(instance);
				} else {
					if (!instance.equals(this.currentInstances.get(id))) {
						this.currentInstances.put(id, instance);
						this.onInstanceChanged(instance);
					}
				}
			}

			// detect removed instances
			for (Iterator<Map.Entry<String, Instance>> it = this.currentInstances.entrySet().iterator(); it.hasNext(); ) {
				String id = it.next().getKey();
				if (!newIds.contains(id)) {
					it.remove();
					this.onInstanceRemoved(id);
				}
			}
			logger.info(
					"Instances updated from version {} to version {}: {}",
					new Object [] { this.currentVersion, version, this.currentInstances.values() }
					);
			this.currentVersion = version;
		}
	}
}
