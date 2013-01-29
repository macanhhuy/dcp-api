package org.jboss.dcp.api.cache;

import java.util.Set;

import javax.ejb.Singleton;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;

import org.jboss.dcp.api.rest.SearchRestService;

/**
 * Cache used to cache index names inside {@link SearchRestService}.
 * 
 * @author Vlastimil Elias (velias at redhat dot com)
 */
@Named("indexNamesCache")
@ApplicationScoped
@Singleton
public class IndexNamesCache extends TimedCacheBase<Set<String>> {

	protected IndexNamesCache() {
		super(20L * 1000L);
	}

}
