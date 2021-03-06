/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 */
package org.jboss.dcp.api.rest;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import junit.framework.Assert;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.indices.IndexMissingException;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHitField;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.internal.InternalSearchHitField;
import org.jboss.dcp.api.DcpContentObjectFields;
import org.jboss.dcp.api.model.FacetValue;
import org.jboss.dcp.api.model.PastIntervalValue;
import org.jboss.dcp.api.model.QuerySettings;
import org.jboss.dcp.api.model.QuerySettings.Filters;
import org.jboss.dcp.api.model.SortByValue;
import org.jboss.dcp.api.service.SearchService;
import org.jboss.dcp.api.service.StatsRecordType;
import org.jboss.dcp.api.service.SystemInfoService;
import org.jboss.dcp.api.testtools.TestUtils;
import org.jboss.dcp.api.util.QuerySettingsParser;
import org.jboss.dcp.api.util.SearchUtils;
import org.jboss.resteasy.plugins.providers.atom.Entry;
import org.jboss.resteasy.plugins.providers.atom.Feed;
import org.jboss.resteasy.specimpl.MultivaluedMapImpl;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Unit test for {@link FeedRestService}
 * 
 * @author Vlastimil Elias (velias at redhat dot com)
 */
public class FeedRestServiceTest {

	@Test
	public void feed_permissions() {
		TestUtils.assertPermissionGuest(FeedRestService.class, "feed", UriInfo.class);
	}

	@Test
	public void patchQuerySettings() {
		FeedRestService tested = getTested();

		try {
			tested.patchQuerySettings(null);
			Assert.fail("NullPointerException expected");
		} catch (NullPointerException e) {
			// OK
		}

		// case - set some values on empty QuerySettings
		{
			QuerySettings qs = new QuerySettings();
			tested.patchQuerySettings(qs);

			// assert patched fields
			Assert.assertEquals(false, qs.isQueryHighlight());
			Assert.assertEquals(new Integer(0), qs.getFilters().getFrom());
			Assert.assertEquals(new Integer(20), qs.getFilters().getSize());
			Assert.assertEquals(SortByValue.NEW, qs.getSortBy());
			Assert.assertEquals(9, qs.getFields().size());
			Assert.assertNull(qs.getFilters().getActivityDateFrom());
			Assert.assertNull(qs.getFilters().getActivityDateTo());
			Assert.assertNull(qs.getFilters().getActivityDateInterval());

			// assert preserved fields
			Assert.assertEquals(null, qs.getFacets());
			Assert.assertNull(qs.getFilters().getContentType());
			Assert.assertNull(qs.getFilters().getDcpContentProvider());
			Assert.assertNull(qs.getFilters().getContributors());
			Assert.assertNull(qs.getFilters().getDcpTypes());
			Assert.assertNull(qs.getFilters().getProjects());
			Assert.assertNull(qs.getFilters().getTags());
		}

		// case - set and reset some values on nonempty QuerySettings
		{
			QuerySettings qs = new QuerySettings();
			qs.addFacet(FacetValue.ACTIVITY_DATES_HISTOGRAM);
			qs.addFacet(FacetValue.PER_PROJECT_COUNTS);
			qs.addField("field1");
			qs.addField("field2");
			qs.setQueryHighlight(true);
			qs.setQuery("Querry");
			// unsupported sort by must be changed
			qs.setSortBy(SortByValue.OLD);
			Filters f = qs.getFiltersInit();
			f.setFrom(50);
			f.setSize(150);
			f.setActivityDateFrom(12l);
			f.setActivityDateTo(20l);
			f.setActivityDateInterval(PastIntervalValue.TEST);
			f.setContentType("ct");
			f.setDcpContentProvider("cp");

			tested.patchQuerySettings(qs);

			// assert patched fields
			Assert.assertEquals(0, qs.getFacets().size());
			Assert.assertEquals(false, qs.isQueryHighlight());
			Assert.assertEquals(new Integer(0), qs.getFilters().getFrom());
			Assert.assertEquals(new Integer(20), qs.getFilters().getSize());
			Assert.assertEquals(SortByValue.NEW, qs.getSortBy());
			Assert.assertEquals(9, qs.getFields().size());
			Assert.assertNull(qs.getFilters().getActivityDateFrom());
			Assert.assertNull(qs.getFilters().getActivityDateTo());
			Assert.assertNull(qs.getFilters().getActivityDateInterval());

			// assert preserved fields
			Assert.assertEquals("Querry", qs.getQuery());
			Assert.assertEquals(0, qs.getFacets().size());
			Assert.assertEquals("ct", qs.getFilters().getContentType());
			Assert.assertEquals("cp", qs.getFilters().getDcpContentProvider());

		}

		// case - SortByValue.NEW_CREATION allowed in QuerySettings
		{
			QuerySettings qs = new QuerySettings();
			qs.setSortBy(SortByValue.NEW_CREATION);

			tested.patchQuerySettings(qs);

			Assert.assertEquals(SortByValue.NEW_CREATION, qs.getSortBy());

		}

	}

	@Test
	public void feed() throws IOException {
		FeedRestService tested = getTested();
		tested.querySettingsParser = Mockito.mock(QuerySettingsParser.class);
		tested.searchService = Mockito.mock(SearchService.class);
		tested.systemInfoService = Mockito.mock(SystemInfoService.class);

		// case - correct processing sequence, null search hits, feed title without params
		{
			Mockito.reset(tested.querySettingsParser, tested.searchService);
			UriInfo uriInfo = Mockito.mock(UriInfo.class);
			MultivaluedMap<String, String> qp = new MultivaluedMapImpl<String, String>();
			Mockito.when(uriInfo.getQueryParameters()).thenReturn(qp);
			QuerySettings qs = new QuerySettings();
			Mockito.when(tested.querySettingsParser.parseUriParams(qp)).thenReturn(qs);
			prepareSearchResponseMocks(tested, qs, null);

			Object response = tested.feed(uriInfo);

			feedBasicAsserts(tested, uriInfo, qp, qs, response);

			Feed feed = (Feed) response;
			Assert.assertEquals(0, feed.getEntries().size());
			Assert.assertEquals("DCP whole content feed", feed.getTitle());
			Assert.assertNotNull(feed.getUpdated());
			Assert.assertNotNull(feed.getGenerator());
		}

		// case - correct processing sequence, empty search hits, feed title passed from outside
		{
			Mockito.reset(tested.querySettingsParser, tested.searchService);
			UriInfo uriInfo = Mockito.mock(UriInfo.class);
			MultivaluedMap<String, String> qp = new MultivaluedMapImpl<String, String>();
			qp.putSingle(FeedRestService.REQPARAM_FEED_TITLE, "test feed title");
			Mockito.when(uriInfo.getQueryParameters()).thenReturn(qp);
			QuerySettings qs = new QuerySettings();
			Mockito.when(tested.querySettingsParser.parseUriParams(qp)).thenReturn(qs);
			SearchHit[] ha = new SearchHit[0];
			prepareSearchResponseMocks(tested, qs, ha);

			Object response = tested.feed(uriInfo);

			feedBasicAsserts(tested, uriInfo, qp, qs, response);

			Feed feed = (Feed) response;
			Assert.assertEquals(0, feed.getEntries().size());
			Assert.assertEquals("test feed title", feed.getTitle());
			Assert.assertNotNull(feed.getUpdated());
			Assert.assertNotNull(feed.getGenerator());
		}

		// case - correct processing sequence with hits, feed title from param
		{
			Mockito.reset(tested.querySettingsParser, tested.searchService);
			UriInfo uriInfo = Mockito.mock(UriInfo.class);
			MultivaluedMap<String, String> qp = new MultivaluedMapImpl<String, String>();
			Mockito.when(uriInfo.getQueryParameters()).thenReturn(qp);
			QuerySettings qs = new QuerySettings();
			qs.getFiltersInit().addProject("as7");
			Mockito.when(tested.querySettingsParser.parseUriParams(qp)).thenReturn(qs);

			// hit 1 - only dcp_description, no dcp_content
			SearchHit hit1 = Mockito.mock(SearchHit.class);
			Mockito.when(hit1.getId()).thenReturn("hit1");
			Map<String, SearchHitField> fields = new HashMap<String, SearchHitField>();
			Mockito.when(hit1.getFields()).thenReturn(fields);
			putSearchHitField(fields, DcpContentObjectFields.DCP_TITLE, "My title");
			putSearchHitField(fields, DcpContentObjectFields.DCP_URL_VIEW, "http://url1");
			putSearchHitField(fields, DcpContentObjectFields.DCP_DESCRIPTION, "My description");
			putSearchHitField(fields, DcpContentObjectFields.DCP_CREATED, "2012-11-02T12:55:44Z");
			putSearchHitField(fields, DcpContentObjectFields.DCP_LAST_ACTIVITY_DATE, "2012-11-01T12:55:44Z");
			putSearchHitField(fields, DcpContentObjectFields.DCP_CONTRIBUTORS, "John Doe <j@d.com>", "My Dear <my@de.org>");
			putSearchHitField(fields, DcpContentObjectFields.DCP_TAGS, "tag1", "tag2");

			// hit 2 - both dcp_description and dcp_content (with valid dcp_content_type), invalid and empty date fields, no
			// contributors nor tags
			SearchHit hit2 = Mockito.mock(SearchHit.class);
			Mockito.when(hit2.getId()).thenReturn("hit2");
			Map<String, SearchHitField> fields2 = new HashMap<String, SearchHitField>();
			Mockito.when(hit2.getFields()).thenReturn(fields2);
			putSearchHitField(fields2, DcpContentObjectFields.DCP_TITLE, "My title 2");
			putSearchHitField(fields2, DcpContentObjectFields.DCP_DESCRIPTION, "My description 2");
			putSearchHitField(fields2, DcpContentObjectFields.DCP_CONTENT, "My content 2");
			putSearchHitField(fields2, DcpContentObjectFields.DCP_CONTENT_TYPE, "text/html");
			putSearchHitField(fields2, DcpContentObjectFields.DCP_CREATED, "invaliddate");

			// hit 3 - dcp_content (with invalid dcp_content_type)
			SearchHit hit3 = Mockito.mock(SearchHit.class);
			Mockito.when(hit3.getId()).thenReturn("hit3");
			Map<String, SearchHitField> fields3 = new HashMap<String, SearchHitField>();
			Mockito.when(hit3.getFields()).thenReturn(fields3);
			putSearchHitField(fields3, DcpContentObjectFields.DCP_TITLE, "My title 3");
			putSearchHitField(fields3, DcpContentObjectFields.DCP_CONTENT, "My content 3");
			putSearchHitField(fields3, DcpContentObjectFields.DCP_CONTENT_TYPE, "bleble");

			SearchHit[] ha = new SearchHit[] { hit1, hit2, hit3 };
			prepareSearchResponseMocks(tested, qs, ha);

			Object response = tested.feed(uriInfo);

			feedBasicAsserts(tested, uriInfo, qp, qs, response);

			Feed feed = (Feed) response;
			Assert.assertEquals("DCP content feed for criteria project=[as7]", feed.getTitle());
			Assert.assertNotNull(feed.getUpdated());
			Assert.assertNotNull(feed.getGenerator());

			Assert.assertEquals(3, feed.getEntries().size());
			{
				Entry entry = feed.getEntries().get(0);
				Assert.assertEquals("dcp:content:id:hit1", entry.getId().toString());
				Assert.assertEquals("My title", entry.getTitle());
				Assert.assertEquals(1, entry.getLinks().size());
				Assert.assertEquals(SearchUtils.dateFromISOString("2012-11-02T12:55:44Z", false), entry.getPublished());
				Assert.assertEquals(SearchUtils.dateFromISOString("2012-11-01T12:55:44Z", false), entry.getUpdated());
				Assert.assertEquals("http://url1", entry.getLinks().get(0).getHref().toString());
				Assert.assertNull(entry.getSummary());
				Assert.assertEquals("My description", entry.getContent().getText());
				Assert.assertEquals(MediaType.TEXT_PLAIN_TYPE, entry.getContent().getType());
				Assert.assertEquals(2, entry.getAuthors().size());
				Assert.assertEquals("John Doe", entry.getAuthors().get(0).getName());
				Assert.assertEquals("My Dear", entry.getAuthors().get(1).getName());
				// no emails in feed !
				Assert.assertNull(entry.getAuthors().get(0).getEmail());
				Assert.assertNull(entry.getAuthors().get(1).getEmail());
				Assert.assertEquals(0, entry.getContributors().size());
				Assert.assertEquals(2, entry.getCategories().size());
				Assert.assertEquals("tag1", entry.getCategories().get(0).getTerm());
				Assert.assertEquals("tag2", entry.getCategories().get(1).getTerm());
			}

			{
				Entry entry = feed.getEntries().get(1);
				Assert.assertEquals("dcp:content:id:hit2", entry.getId().toString());
				Assert.assertEquals("My title 2", entry.getTitle());
				Assert.assertEquals(0, entry.getLinks().size());
				Assert.assertNull(entry.getPublished());
				Assert.assertNull(entry.getUpdated());
				Assert.assertEquals("My description 2", entry.getSummary());
				Assert.assertEquals("My content 2", entry.getContent().getText());
				Assert.assertEquals(MediaType.TEXT_HTML_TYPE, entry.getContent().getType());
				// one author added because ATOM spec requires it!
				Assert.assertEquals(1, entry.getAuthors().size());
				Assert.assertEquals("unknown", entry.getAuthors().get(0).getName());
				Assert.assertNull(entry.getAuthors().get(0).getEmail());
				Assert.assertEquals(0, entry.getContributors().size());
				Assert.assertEquals(0, entry.getCategories().size());
			}

			{
				Entry entry = feed.getEntries().get(2);
				Assert.assertEquals("dcp:content:id:hit3", entry.getId().toString());
				Assert.assertEquals("My title 3", entry.getTitle());
				Assert.assertEquals(0, entry.getLinks().size());
				Assert.assertNull(entry.getPublished());
				Assert.assertNull(entry.getUpdated());
				Assert.assertNull(entry.getSummary());
				Assert.assertEquals("My content 3", entry.getContent().getText());
				// default type used here because invalid in input
				Assert.assertEquals(MediaType.TEXT_PLAIN_TYPE, entry.getContent().getType());
				// one author added because ATOM spec requires it!
				Assert.assertEquals(1, entry.getAuthors().size());
				Assert.assertEquals("unknown", entry.getAuthors().get(0).getName());
				Assert.assertNull(entry.getAuthors().get(0).getEmail());
				Assert.assertEquals(0, entry.getContributors().size());
				Assert.assertEquals(0, entry.getCategories().size());
			}

		}

	}

	private void putSearchHitField(Map<String, SearchHitField> fields, String name, Object... values) {
		fields.put(name, new InternalSearchHitField(name, Arrays.asList(values)));
	}

	private void feedBasicAsserts(FeedRestService tested, UriInfo uriInfo, MultivaluedMap<String, String> qp,
			QuerySettings qs, Object response) {
		Mockito.verify(uriInfo, Mockito.times(2)).getQueryParameters();
		Mockito.verify(tested.querySettingsParser).parseUriParams(qp);
		Mockito.verify(tested.searchService).performSearch(Mockito.eq(qs), Mockito.notNull(String.class),
				Mockito.eq(StatsRecordType.FEED));
		Assert.assertTrue("Bad class instead of Feed: " + response.getClass().getName(), response instanceof Feed);
	}

	@Test
	public void feed_errorhandling() throws IOException {
		FeedRestService tested = getTested();
		tested.querySettingsParser = Mockito.mock(QuerySettingsParser.class);
		tested.searchService = Mockito.mock(SearchService.class);
		tested.systemInfoService = Mockito.mock(SystemInfoService.class);

		// case - incorrect input
		{
			Object response = tested.feed(null);
			TestUtils.assertResponseStatus(response, Status.INTERNAL_SERVER_ERROR);
		}

		// case - error handling for invalid request value
		{
			Mockito.reset(tested.querySettingsParser, tested.searchService);
			UriInfo uriInfo = Mockito.mock(UriInfo.class);
			MultivaluedMap<String, String> qp = new MultivaluedMapImpl<String, String>();
			Mockito.when(uriInfo.getQueryParameters()).thenReturn(qp);
			Mockito.when(tested.querySettingsParser.parseUriParams(qp)).thenThrow(
					new IllegalArgumentException("test exception"));
			Object response = tested.feed(uriInfo);
			TestUtils.assertResponseStatus(response, Status.BAD_REQUEST);
		}

		// case - error handling for index not found exception
		{
			Mockito.reset(tested.querySettingsParser, tested.searchService);
			UriInfo uriInfo = Mockito.mock(UriInfo.class);
			MultivaluedMap<String, String> qp = new MultivaluedMapImpl<String, String>();
			Mockito.when(uriInfo.getQueryParameters()).thenReturn(qp);
			QuerySettings qs = new QuerySettings();
			Mockito.when(tested.querySettingsParser.parseUriParams(qp)).thenReturn(qs);
			Mockito.when(
					tested.searchService.performSearch(Mockito.eq(qs), Mockito.notNull(String.class),
							Mockito.eq(StatsRecordType.FEED))).thenThrow(new IndexMissingException(null));
			Object response = tested.feed(uriInfo);
			TestUtils.assertResponseStatus(response, Status.NOT_FOUND);
		}

		// case - error handling for other exceptions
		{
			Mockito.reset(tested.querySettingsParser, tested.searchService);
			UriInfo uriInfo = Mockito.mock(UriInfo.class);
			MultivaluedMap<String, String> qp = new MultivaluedMapImpl<String, String>();
			Mockito.when(uriInfo.getQueryParameters()).thenReturn(qp);
			QuerySettings qs = new QuerySettings();
			Mockito.when(tested.querySettingsParser.parseUriParams(qp)).thenReturn(qs);
			Mockito.when(
					tested.searchService.performSearch(Mockito.eq(qs), Mockito.notNull(String.class),
							Mockito.eq(StatsRecordType.FEED))).thenThrow(new RuntimeException());
			Object response = tested.feed(uriInfo);
			TestUtils.assertResponseStatus(response, Status.INTERNAL_SERVER_ERROR);
		}
	}

	/**
	 * @param tested
	 * @param qs
	 */
	private void prepareSearchResponseMocks(FeedRestService tested, QuerySettings qs, SearchHit[] hitsArray) {
		SearchResponse sr = Mockito.mock(SearchResponse.class);
		SearchHits searchHits = Mockito.mock(SearchHits.class);
		Mockito.when(searchHits.getHits()).thenReturn(hitsArray);
		Mockito.when(sr.getHits()).thenReturn(searchHits);
		Mockito.when(
				tested.searchService.performSearch(Mockito.eq(qs), Mockito.notNull(String.class),
						Mockito.eq(StatsRecordType.FEED))).thenReturn(sr);
	}

	@Test
	public void constructFeedTitle() throws URISyntaxException {
		FeedRestService tested = getTested();

		{
			QuerySettings querySettings = new QuerySettings();
			Assert.assertEquals("DCP whole content feed", tested.constructFeedTitle(querySettings).toString());
		}

		// case - string param
		{
			QuerySettings querySettings = new QuerySettings();
			querySettings.getFiltersInit().setContentType("conetnt_type");
			Assert.assertEquals("DCP content feed for criteria type=conetnt_type", tested.constructFeedTitle(querySettings)
					.toString());
		}

		// case - list param
		{
			QuerySettings querySettings = new QuerySettings();
			querySettings.getFiltersInit().addProject("as7");
			Assert.assertEquals("DCP content feed for criteria project=[as7]", tested.constructFeedTitle(querySettings)
					.toString());
			querySettings.getFiltersInit().addProject("aerogear");
			Assert.assertEquals("DCP content feed for criteria project=[as7, aerogear]",
					tested.constructFeedTitle(querySettings).toString());
		}

		// case - multiple params
		{
			QuerySettings querySettings = new QuerySettings();
			querySettings.getFiltersInit().addProject("as7");
			querySettings.getFiltersInit().addProject("aerogear");
			querySettings.getFiltersInit().addTag("tag1");
			querySettings.getFiltersInit().addTag("tag2");
			querySettings.getFiltersInit().setContentType("content_type");
			querySettings.getFiltersInit().setDcpContentProvider("jbossorg");
			querySettings.getFiltersInit().addDcpType("issue");
			querySettings.getFiltersInit().addDcpType("blogpost");
			querySettings.getFiltersInit().addContributor("John Doe <john@doe.org>");
			querySettings.setQuery("querry me fulltext");
			querySettings.setSortBy(SortByValue.NEW_CREATION);
			Assert
					.assertEquals(
							"DCP content feed for criteria project=[as7, aerogear] and contributor=[John Doe <john@doe.org>] and tag=[tag1, tag2] and dcp_type=[issue, blogpost] and type=content_type and content_provider=jbossorg and query='querry me fulltext' and sortBy=new-create",
							tested.constructFeedTitle(querySettings).toString());
		}

	}

	/**
	 * @return
	 */
	private FeedRestService getTested() {
		FeedRestService tested = new FeedRestService();
		tested.log = Logger.getLogger("test logger");
		return tested;
	}

}
