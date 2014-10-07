package com.rknowsys.portal.search.elastic;

import com.liferay.portal.kernel.json.JSONArray;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.search.*;
import com.liferay.portal.kernel.search.facet.Facet;
import com.liferay.portal.kernel.search.facet.MultiValueFacet;
import com.liferay.portal.kernel.search.facet.RangeFacet;
import com.liferay.portal.kernel.search.facet.collector.FacetCollector;
import com.liferay.portal.kernel.search.facet.config.FacetConfiguration;
import com.liferay.portal.kernel.util.ArrayUtil;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.StringUtil;
import com.rknowsys.portal.search.elastic.client.ClientFactory;
import com.rknowsys.portal.search.elastic.facet.ElasticsearchFacetFieldCollector;
import com.rknowsys.portal.search.elastic.facet.LiferayFacetParser;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.facet.FacetBuilder;
import org.elasticsearch.search.facet.FacetBuilders;
import org.elasticsearch.search.facet.Facets;
import org.elasticsearch.search.facet.filter.FilterFacetBuilder;
import org.elasticsearch.search.sort.SortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;

import java.util.*;

public class ElasticsearchIndexSearcher implements IndexSearcher {

    private ClientFactory clientFactory;

    @Override
    public Hits search(SearchContext searchContext, Query query) throws SearchException {
        try {
            Client client = getClient();

            SearchRequestBuilder searchRequestBuilder = client.prepareSearch(
                    String.valueOf(searchContext.getCompanyId()));

            QueryBuilder queryBuilder = QueryBuilders.queryString(query.toString());

            searchRequestBuilder.setQuery(queryBuilder);

            searchRequestBuilder.setTypes("LiferayDocuments");

            addFacetCollectorsToSearch(searchContext, searchRequestBuilder);

            addSortToSearch(searchContext.getSorts(),searchRequestBuilder);

            SearchRequest searchRequest = searchRequestBuilder.request();

            ActionFuture<SearchResponse> future = client.search(searchRequest);

            SearchResponse searchResponse = future.actionGet();

            updateFacetCollectors(searchContext, searchResponse);

            Hits hits = processSearchHits(
                    searchResponse.getHits(), query.getQueryConfig());

            hits.setQuery(query);

            TimeValue timeValue = searchResponse.getTook();

            hits.setSearchTime((float) timeValue.getSecondsFrac());
            return hits;
        } catch (Exception e) {
            throw new SearchException(e);
        }
    }




    @Override
    public Hits search(String searchEngineId, long companyId, Query query, Sort[] sort, int start, int end) throws SearchException {

        try {
            Client client = getClient();

            SearchRequestBuilder searchRequestBuilder = client.prepareSearch(
                    String.valueOf(companyId));

            QueryBuilder queryBuilder = QueryBuilders.queryString(query.toString());

            searchRequestBuilder.setQuery(queryBuilder);

            searchRequestBuilder.setTypes("LiferayDocuments");

            addSortToSearch(sort,searchRequestBuilder);

            SearchRequest searchRequest = searchRequestBuilder.request();

            ActionFuture<SearchResponse> future = client.search(searchRequest);

            SearchResponse searchResponse = future.actionGet();


            Hits hits = processSearchHits(
                    searchResponse.getHits(), query.getQueryConfig());

            hits.setQuery(query);

            TimeValue timeValue = searchResponse.getTook();

            hits.setSearchTime((float) timeValue.getSecondsFrac());
            return hits;
        } catch (Exception e) {
            throw new SearchException(e);
        }
    }

    @Override
    public String spellCheckKeywords(SearchContext searchContext) {
        return StringPool.BLANK;
    }

    @Override
    public Map<String, List<String>> spellCheckKeywords(
            SearchContext searchContext, int max) {

        return Collections.emptyMap();
    }

    @Override
    public String[] suggestKeywordQueries(
            SearchContext searchContext, int max) {

        return new String[0];
    }


    protected Document processSearchHit(SearchHit hit) {
        Document document = new DocumentImpl();

        Map<String, Object> source = hit.getSource();

        for (String fieldName :
                source.keySet()) {

            String val = (String) source.get(fieldName);
            Field field = new Field(
                    fieldName,
                    new String[]{val}
            );

            document.add(field);

        }

        return document;
    }

    protected Hits processSearchHits(
            SearchHits searchHits, QueryConfig queryConfig) {

        Hits hits = new HitsImpl();

        List<Document> documents = new ArrayList<Document>();
        Set<String> queryTerms = new HashSet<String>();
        List<Float> scores = new ArrayList<Float>();

        if (searchHits.totalHits() > 0) {
            SearchHit[] searchHitsArray = searchHits.getHits();

            for (SearchHit searchHit : searchHitsArray) {
                Document document = processSearchHit(searchHit);
                documents.add(document);
                scores.add(searchHit.getScore());
            }
        }

        hits.setDocs(documents.toArray(new Document[documents.size()]));
        hits.setLength((int) searchHits.getTotalHits());
        hits.setQueryTerms(queryTerms.toArray(new String[queryTerms.size()]));
        hits.setScores(scores.toArray(new Float[scores.size()]));

        return hits;
    }

    protected void updateFacetCollectors(
            SearchContext searchContext, SearchResponse searchResponse) {

        Map<String, Facet> facetsMap = searchContext.getFacets();

        for (Facet facet : facetsMap.values()) {
            if (facet.isStatic()) {
                continue;
            }

            Facets facets = searchResponse.getFacets();

            org.elasticsearch.search.facet.Facet elasticsearchFacet =
                    facets.facet(facet.getFieldName());

            FacetCollector facetCollector =
                    new ElasticsearchFacetFieldCollector(elasticsearchFacet);

            facet.setFacetCollector(facetCollector);
        }
    }

    private void addFacetCollectorsToSearch(SearchContext searchContext, SearchRequestBuilder searchRequestBuilder) {
        Map<String, Facet> facets = searchContext.getFacets();
        for (Facet facet : facets.values()) {
            FacetBuilder facetBuilder = null;
            if (facet instanceof MultiValueFacet) {
                facetBuilder = LiferayFacetParser.getFacetBuilder((MultiValueFacet) facet);
            } else if (facet instanceof RangeFacet) {
                facetBuilder = LiferayFacetParser.getFacetBuilder((RangeFacet) facet);
            }
            if (facetBuilder != null) {
                searchRequestBuilder.addFacet(facetBuilder);
            }
        }

    }

    private void addSortToSearch(Sort[] sorts, SearchRequestBuilder searchRequestBuilder) {
        if (sorts == null) {
            return;
        }
        for(Sort sort : sorts) {
            SortBuilder sortBuilder = SortBuilders.fieldSort(sort.getFieldName()).ignoreUnmapped(true)
                    .order(sort.isReverse()? SortOrder.DESC: SortOrder.ASC);
            searchRequestBuilder.addSort(sortBuilder);

        }
    }

    public void setClientFactory(ClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    private Client getClient() {
        return clientFactory.getClient();
    }


}