/*
 * JBoss, Home of Professional Open Source
 * Copyright 2013 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 */
package org.jboss.dcp.api.reindexer;

import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.index.query.TermsFilterBuilder;
import org.jboss.dcp.api.service.ProviderService;
import org.jboss.dcp.api.service.SearchClientService;

/**
 * Task used to update document in ES search indices by selecting them oved values in defined field field. Used for
 * example when project name is changed (so we reindex over project code in dcp_project field), or contributor
 * configuration is splitted (so we reindex over dcp_contributors value).
 * <p>
 * All documents for given value in given field are loaded from all ES indices (only indices where DCP content is
 * stored), all preprocessors are applied to content, and then it is stored back to the ES index.
 * 
 * @author Vlastimil Elias (velias at redhat dot com)
 */
public class RenormalizeByEsValueTask extends RenormalizeTaskBase {

	protected String esField;
	protected String[] esValues;

	/**
	 * @param providerService
	 * @param searchClientService
	 * @param esField field in ElasticSarch indices to search over
	 * @param esValues values for given esField to reindex documents for
	 */
	public RenormalizeByEsValueTask(ProviderService providerService, SearchClientService searchClientService,
			String esField, String[] esValues) {
		super(providerService, searchClientService);
		this.esField = esField;
		this.esValues = esValues;
	}

	/**
	 * Constructor for unit tests.
	 */
	protected RenormalizeByEsValueTask() {
	}

	@Override
	protected void addFilters(SearchRequestBuilder srb) {
		srb.setFilter(new TermsFilterBuilder(esField, esValues));
	}

}
