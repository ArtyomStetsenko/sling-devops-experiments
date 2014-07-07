package org.apache.sling.samples.test;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Session;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Hack that moves the initial content provided by this bundle
 *  under /sling-cfg/N where we'll find the apps and libs folder
 *  that are specific to this configuration.
 *  The idea is to use specific libs and apps folders for each 
 *  Sling config, so that several configs can live in the same 
 *  content repository. We'll need to find a better way ;-)  
 */
@Component
public class InitialContentMoveHack implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(InitialContentMoveHack.class);

	private static final String SOURCE = "/sample-test";
	private static final String DEST = "/sling-cfg"; // real dest will be /sling-cfg/<config>

	@Reference
	private ResourceResolverFactory resourceResolverFactory;
	
	private String config;
	
	@Activate
	protected void activate(ComponentContext ctx) {
        config = ctx.getBundleContext().getProperty("sling.devops.config");
        if(config == null) {
            logger.info("Config is null, not moving anything.");
        } else {
            final Thread t = new Thread(this, getClass().getSimpleName());
            t.setDaemon(true);
            t.start();
        }
	}
	
	@Override
    public void run() {
        logger.info("Waiting for initial content in thread {}", Thread.currentThread().getName());
	    try {
	        long lastReport = 0;
	        while(true) {
	            final String problem = tryMoving();
	            if(problem == null) {
	                logger.info("Move successful, config={}", config);
	                break;
	            }
	            if(System.currentTimeMillis() - lastReport > 10000L) {
	                logger.info("Move not successful: {}", problem);
	            }
	            Thread.sleep(1000);
	        }
	    } catch(Exception e) {
	        logger.warn("Exception in run()", e);
	    }
	    logger.info("Thread ends: {}", Thread.currentThread().getName());
    }
	
	private String tryMoving() {
	    String problem = null;
	    
		ResourceResolver adminResolver = null;
		try {
			adminResolver = resourceResolverFactory.getAdministrativeResourceResolver(null);
			final Session adminSession = adminResolver.adaptTo(Session.class);

			// navigate to source node
			final String[] sourceParts = SOURCE.split("/");
			Node sourceNode = adminSession.getRootNode();
			for (int i = 1; i < sourceParts.length; i++) {
				sourceNode = sourceNode.getNode(sourceParts[i]);
			}

			// create destination node
			final String dest = DEST + '/' + config + '/';
			final String[] destParts = dest.split("/");
			Node destNode = adminSession.getRootNode();
			for (int i = 1; i < destParts.length; i++) {
				if (!destNode.hasNode(destParts[i])) destNode.addNode(destParts[i], "sling:Folder");
				destNode = destNode.getNode(destParts[i]);
			}

			// move each child of source node to destination node
			for (final NodeIterator it = sourceNode.getNodes(); it.hasNext(); ) {
				final Node node = it.nextNode();
				logger.info("Node {} will be moved to {}", node.getPath(), destNode.getPath());
				moveNode(adminSession, node, destNode);
			}
			sourceNode.remove();

			adminSession.save();
		} catch (final Exception e) {
			problem = e.toString();
		} finally {
			if (adminResolver != null) {
				adminResolver.close();
			}
		}
		return problem;
	}

	private static void moveNode(final Session session, final Node src, final Node dest) throws Exception {
		// base case: src is a leaf or dest does not contain a node with src's name
		if (!src.hasNodes() || !dest.hasNode(src.getName())) {
			if (!dest.hasNode(src.getName())) { // leaf node: no overwrite
				logger.info("Moving node {} inside {}", src.getPath(), dest.getPath());
				session.move(src.getPath(), dest.getPath() + '/' + src.getName());
			} else logger.info(
						"Not moving leaf node {} inside {}: node with the same name already exists",
						src.getPath(),
						dest.getPath()
						);
		} else {
			final Node childDest = dest.getNode(src.getName());
			for (final NodeIterator it = src.getNodes(); it.hasNext(); ) {
				moveNode(session, it.nextNode(), childDest);
			}
			src.remove();
		}
	}
}
