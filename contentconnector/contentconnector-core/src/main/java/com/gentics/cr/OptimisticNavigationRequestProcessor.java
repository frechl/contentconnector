package com.gentics.cr;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import com.gentics.api.lib.datasource.Datasource;
import com.gentics.api.lib.datasource.Datasource.Sorting;
import com.gentics.api.lib.datasource.DatasourceException;
import com.gentics.api.lib.etc.ObjectTransformer;
import com.gentics.api.lib.exception.NodeException;
import com.gentics.api.lib.exception.ParserException;
import com.gentics.api.lib.expressionparser.ExpressionParserException;
import com.gentics.api.lib.expressionparser.filtergenerator.DatasourceFilter;
import com.gentics.api.lib.resolving.Resolvable;
import com.gentics.api.portalnode.connector.PortalConnectorFactory;
import com.gentics.cr.exceptions.CRException;
import com.gentics.cr.util.ArrayHelper;
import com.gentics.cr.util.PNSortingComparator;

/**
 * 
 * 
 * Last changed: $Date: 2013-07-18 15:24:02 +0200 (Do, 18 Jul 2013) $
 * 
 * @version $Revision: 541 $
 * @author $Author: l.osang@gentics.com $
 * 
 */
public class OptimisticNavigationRequestProcessor extends RequestProcessor {

	private static Logger logger = Logger.getLogger(OptimisticNavigationRequestProcessor.class);

	private HashMap<String, Resolvable> resolvables = null;

	/**
	 * Key for
	 * {@link OptimisticNavigationRequestProcessor#folderIdContentmapName} in
	 * the config
	 */
	private static final String FOLDER_ID_KEY = "folder_id.key";

	/**
	 * String of content map folder id column
	 */
	private static String folderIdContentmapName = "folder_id";

	/**
	 * Create a new instance of CRRequestProcessor.
	 * 
	 * @param config
	 * @throws CRException
	 */
	public OptimisticNavigationRequestProcessor(CRConfig config) throws CRException {
		super(config);

		if (!StringUtils.isEmpty(config.getString(FOLDER_ID_KEY))) {
			this.folderIdContentmapName = config.getString(FOLDER_ID_KEY);
		}

	}

	/**
	 * 
	 * Fetch the matching objects using the given CRRequest.
	 * 
	 * @param request
	 *            CRRequest
	 * @param doNavigation
	 *            defines if to fetch children
	 * @return resulting objects
	 * @throws CRException
	 *             TODO javadocs
	 */
	public Collection<CRResolvableBean> getObjects(final CRRequest request, final boolean doNavigation)
			throws CRException {
		Datasource ds = null;
		DatasourceFilter dsFilter;
		Vector<CRResolvableBean> collection = new Vector<CRResolvableBean>();
		if (request != null) {

			// Parse the given expression and create a datasource filter
			try {
				ds = this.config.getDatasource();
				if (ds == null) {
					throw (new DatasourceException("No Datasource available."));
				}

				dsFilter = request.getPreparedFilter(config, ds);

				// add base resolvables
				if (this.resolvables != null) {
					for (Iterator<String> it = this.resolvables.keySet().iterator(); it.hasNext();) {
						String name = it.next();
						dsFilter.addBaseResolvable(name, this.resolvables.get(name));
					}
				}

				String[] prefillAttributes = request.getAttributeArray();
				prefillAttributes = ArrayHelper.removeElements(prefillAttributes, "contentid", "updatetimestamp");
				// do the query
				Collection<Resolvable> col = this.toResolvableCollection(ds.getResult(dsFilter, prefillAttributes,
						request.getStart().intValue(), request.getCount().intValue(), request.getSorting()));

				// convert all objects to serializeable beans
				if (col != null) {
					for (Iterator<Resolvable> it = col.iterator(); it.hasNext();) {
						CRResolvableBean crBean = new CRResolvableBean(it.next(), request.getAttributeArray());
						collection.add(this.replacePlinks(crBean, request));
					}
					// IF NAVIGAION WE PROCESS THE FAST NAVIGATION ALGORITHM
					if (doNavigation) {

						// Build the request to fetch all possible children
						CRRequest childReq = new CRRequest();
						// set children attributes (folder_id)
						String[] fetchAttributesForChildren = { folderIdContentmapName };
						childReq.setAttributeArray(fetchAttributesForChildren);
						childReq.setRequestFilter(request.getChildFilter());
						childReq.setSortArray(new String[] { folderIdContentmapName + ":asc" });

						Collection<CRResolvableBean> children = getObjects(childReq, false);

						// get original sorting order for child sorting
						// sort childrepositories with that
						Sorting[] sorting = request.getSorting();

						// those Resolvables will be filled with specified
						// attributes
						List<Resolvable> itemsToPrefetch = new Vector<Resolvable>();

						HashMap<String, Vector<CRResolvableBean>> prepareFolderMap = prepareFolderMap(children);

						for (CRResolvableBean item : collection) {
							// build the tree
							recursiveTreeBuild(item, prepareFolderMap, sorting, itemsToPrefetch);
						}

						// prefetch all necessary attribute that are specified
						// in the request
						PortalConnectorFactory.prefillAttributes(ds, itemsToPrefetch, Arrays.asList(prefillAttributes));
					}
				}
			} catch (ParserException e) {
				logger.error("Error getting filter for Datasource.", e);
				throw new CRException(e);
			} catch (ExpressionParserException e) {
				logger.error("Error getting filter for Datasource.", e);
				throw new CRException(e);
			} catch (DatasourceException e) {
				logger.error("Error getting result from Datasource.", e);
				throw new CRException(e);
			} catch (NodeException e) {
				logger.error("Error getting result from Datasource.", e);
			} finally {
				CRDatabaseFactory.releaseDatasource(ds);
			}
		}
		return collection;
	}

	/**
	 * <p>
	 * prepare the fetched children objects and put them to a prepared map with
	 * this format: <code>(folder_id, Collection children)</code>
	 * 
	 * @param children
	 * @return the prepared HashMap
	 */
	private HashMap<String, Vector<CRResolvableBean>> prepareFolderMap(Collection<CRResolvableBean> children) {

		HashMap<String, Vector<CRResolvableBean>> map = new HashMap<String, Vector<CRResolvableBean>>();

		for (CRResolvableBean crResolvableBean : children) {

			String folder_id = crResolvableBean.getString(folderIdContentmapName);

			if (StringUtils.isNotEmpty(folder_id)) {
				Vector<CRResolvableBean> col = map.get(folder_id);

				if (col == null) {
					col = new Vector<CRResolvableBean>();
					map.put(folder_id, col);
				}

				col.add(crResolvableBean);
			}
		}

		return map;
	}

	/**
	 * Builds the tree and fills the children of the root element
	 * 
	 * @param root
	 *            the element that will be filled with children
	 * @param folderMap
	 * @param sorting
	 * @param itemsToPrefetch
	 */
	private void recursiveTreeBuild(CRResolvableBean root, HashMap<String, Vector<CRResolvableBean>> folderMap,
			Sorting[] sorting, List<Resolvable> itemsToPrefetch) {

		// remove added items from children
		Vector<CRResolvableBean> children = folderMap.get(root.getContentid());

		// brake condition, there are no children for this tree node
		if (ObjectTransformer.isEmpty(children)) {
			return;
		}

		if (sorting != null && sorting.length > 0) {
			sortCollection(children, sorting[0]);
		}

		// fill the actual object with children
		root.fillChildRepository(children);

		for (CRResolvableBean crResolvableBean : children) {

			// recursive call to build children map
			recursiveTreeBuild(crResolvableBean, folderMap, sorting, itemsToPrefetch);

			// fill to items that should be filled with attributes
			itemsToPrefetch.add(crResolvableBean.getResolvable());
		}

	}

	/**
	 * We do the sorting in memory because we cannot fetch the objects sorted
	 * from the database.
	 * 
	 * @param collection
	 * @param sorting
	 */
	private void sortCollection(Vector<CRResolvableBean> collection, Sorting sorting) {
		if (sorting != null) {
			String columnName = sorting.getColumnName();
			int order = sorting.getSortOrder();
			Collections.sort(collection, new PNSortingComparator<CRResolvableBean>(columnName, order));
		}
	}

	@Override
	public void finalize() {
	}

}
