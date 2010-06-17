package org.apache.nutch.kairos.plugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.Map.Entry;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.rpc.ServiceException;

import org.apache.axis.encoding.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.Writable;
import org.apache.nutch.crawl.CrawlDatum;
import org.apache.nutch.crawl.CrawlDbReader;
import org.apache.nutch.crawl.Inlink;
import org.apache.nutch.crawl.Inlinks;
import org.apache.nutch.indexer.IndexingException;
import org.apache.nutch.indexer.IndexingFilter;
import org.apache.nutch.indexer.NutchDocument;
import org.apache.nutch.indexer.lucene.LuceneWriter;
import org.apache.nutch.kairos.Utils;
import org.apache.nutch.parse.Parse;
import org.apache.nutch.util.LogUtil;
import org.apache.nutch.util.StringUtil;
import org.w3c.dom.CharacterData;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import forecite.ForeCiteLocator;
import forecite.ForeCitePortType;

/**
 * The following is the code for the Indexing Filter extension.
 * 
 * @author Markus Haense
 */
public class KairosIndexer implements IndexingFilter {

	/**
	 * Logger
	 */
	public static final Log LOG = LogFactory.getLog(KairosIndexer.class
			.getName());

	/**
	 * Configuration
	 */
	private Configuration conf;

	/**
	 * Confidence for ParsCit output
	 */
	private static final double CONFIDENCE = 0.70;

	/**
	 * Get elements from DOM tree by tag name
	 * 
	 * @param doc
	 * @param name
	 * @return
	 */
	private List<String> getElementsByTagName(Document doc, String name) {
		if (name != null && name.length() > 0) {
			List<String> dataElementList = new LinkedList<String>();

			NodeList nodeList = doc.getElementsByTagName(name);

			for (int i = 0; i < nodeList.getLength(); i++) {
				Element element = (Element) nodeList.item(i);

				double confidence = element == null ? 0 : Double
						.parseDouble(element.getAttribute("confidence"));

				if (confidence >= CONFIDENCE) {
					String data = getCharacterDataFromElement(element);
					data = data.trim();

					if ((!StringUtil.isEmpty(data))) {
						dataElementList.add(data);
					}
				}
			}

			return dataElementList;
		} else {
			return new LinkedList<String>();
		}
	}

	/**
	 * Get the character data from a DOM element
	 * 
	 * @param e
	 * @return
	 */
	private String getCharacterDataFromElement(Element e) {
		Node child = e.getFirstChild();

		if (child instanceof CharacterData) {
			CharacterData cd = (CharacterData) child;

			return cd.getData();
		}

		return "?";
	}

	/**
	 * Find conference metadata
	 * 
	 * @param scholarlyMetadata
	 * @param inlinks
	 */
	private void findConferenceMetadata(ScholarlyMetadata scholarlyMetadata,
			Inlinks inlinks) {
		CrawlDbReader reader = new CrawlDbReader();

		if (inlinks != null) {
			// Get the inlinks iterator
			Iterator<Inlink> it = inlinks.iterator();

			while (it.hasNext()) {
				// Get the next inlink
				Inlink inlink = it.next();

				try {
					// Get the crawl datum from a certain URL
					CrawlDatum datum = reader.get(
							"crawl.dirs/crawl.conference/crawldb/", inlink
									.getFromUrl(), conf);
					if (datum != null) {
						MapWritable metadata = datum.getMetaData();

						if (metadata != null) {
							for (Entry<Writable, Writable> e : metadata
									.entrySet()) {

								if (e.getKey().toString().equals("c_name")) {
									// Set the conference name
									scholarlyMetadata.setConferenceName(e
											.getValue().toString());
								}

								if (e.getKey().toString().equals("c_title")) {
									// Set the conference title
									scholarlyMetadata.setConferenceTitle(e
											.getValue().toString());

								}

								if (e.getKey().toString()
										.equals("c_begin_date")) {
									// Set the conference begin date
									scholarlyMetadata.setConferenceBeginDate(e
											.getValue().toString());
								}

								if (e.getKey().toString().equals("c_end_date")) {
									// Set the conference begin date
									scholarlyMetadata.setConferenceEndDate(e
											.getValue().toString());
								}

								if (e.getKey().toString().equals("c_place")) {
									// Set the conference place
									scholarlyMetadata.setConferencePlace(e
											.getValue().toString());
								}
							}
						}
					}
				} catch (IOException e) {
					e.printStackTrace(LogUtil.getErrorStream(LOG));
				}
			}
		}
	}

	/**
	 * Get the metadata from ParsCit
	 * 
	 * @param scholarlyMetadata
	 * @param text
	 */
	private void getMetadataFromParsCit(ScholarlyMetadata scholarlyMetadata,
			String text) {
		try {
			Properties properties = new Properties();
			properties.load(new FileInputStream(new File(
					"kairos.properties")));
			
			String parscitLocalPath = properties
					.getProperty("parscitLocalPath");

			String parscitRemoteURL = properties
					.getProperty("parscitRemoteURL");

			// Create test temporary file.
			File tempFile = File.createTempFile("ParsCitForeCite", ".txt",
					new File(parscitLocalPath));

			LOG.info(parscitLocalPath);
			LOG.info(parscitRemoteURL);
			LOG.info(text);
			LOG.info(parscitRemoteURL
							+ tempFile.getName());
			
			// Write the text to a temporary file on disk
			BufferedWriter out = new BufferedWriter(new FileWriter(tempFile));
			out.write(text);
			out.flush();
			out.close();

			// ForeCite service
			ForeCiteLocator service = new ForeCiteLocator();
			ForeCitePortType port;

			try {
				// Get the ForeCite port
				port = service.getForeCitePort();

				try {
					// Get the XML records
					String xmlRecords = port.extract_header(parscitRemoteURL
							+ tempFile.getName());

					// Decode it to a human readable form
					xmlRecords = new String(Base64.decode(xmlRecords));

					LOG.info(xmlRecords);
					// If we have XML records
					if (!StringUtil.isEmpty(xmlRecords)) {

						DocumentBuilderFactory dbf = DocumentBuilderFactory
								.newInstance();
						DocumentBuilder db;

						try {
							db = dbf.newDocumentBuilder();
							InputSource is = new InputSource();
							is.setCharacterStream(new StringReader(xmlRecords
									.toString()));

							Document document;
							try {
								document = db.parse(is);

								// Contains the title elements
								List<String> titleList = getElementsByTagName(
										document, "title");

								// Contains the author elements
								List<String> authorList = getElementsByTagName(
										document, "author");

								// Contains the affiliation elements
								List<String> affiliationList = getElementsByTagName(
										document, "affiliation");

								// Get the author(s) in a nice readable form
								String author = Utils.listToString(authorList,
										" ");

								// Get the title in a nice readable form
								String title = Utils.listToString(titleList,
										" ");

								// Get the affiliation(s) in a nice readable
								// form
								String affiliation = Utils.listToString(
										affiliationList, " ");

								// Set the author
								scholarlyMetadata.setAuthor(author);

								// Set the title
								scholarlyMetadata.setTitle(title);

								// Set the affiliation
								scholarlyMetadata.setAffiliation(affiliation);
							} catch (SAXException e) {
								e.printStackTrace(LogUtil.getErrorStream(LOG));
							} catch (IOException e) {
								e.printStackTrace(LogUtil.getErrorStream(LOG));
							}
						} catch (ParserConfigurationException e) {
							e.printStackTrace(LogUtil.getErrorStream(LOG));
						}
					}
				} catch (RemoteException e) {
					e.printStackTrace(LogUtil.getErrorStream(LOG));
				}
			} catch (ServiceException e) {
				e.printStackTrace(LogUtil.getErrorStream(LOG));
			}

			tempFile.delete();
		} catch (IOException e) {
			e.printStackTrace(LogUtil.getWarnStream(LOG));
		}
	}

	public NutchDocument filter(NutchDocument doc, Parse parse,
			org.apache.hadoop.io.Text url, CrawlDatum datum, Inlinks inlinks)
			throws IndexingException {

		// Add the URL -> IMPORTANT!!! otherwise IOException -> NUTCH BUG!!! :(
		doc.add("url", url.toString());

		// Get the content type
		String contentType = parse.getData().getMeta("Content-Type");

		LOG.info("COOOOOOOOOOOOOOOOOOOOOOOOOOO " + contentType);
		
		// Get the index text which contains scholarly paper metadata
		String indexText = parse.getText();

		LOG.info("PAAAAAAAAAAAAAAAA " + indexText);
		
		Collection<ScholarlyMetadata> metadata = new ArrayList<ScholarlyMetadata>();

		// Contains only unique titles
		Set<String> titleSet = new HashSet<String>();

		if (!StringUtil.isEmpty(indexText)) {
			// Contains the conference metadata
			ScholarlyMetadata conferenceMetadata = new ScholarlyMetadata(
					contentType);

			// Find the conference metadata
			findConferenceMetadata(conferenceMetadata, inlinks);

			if (contentType.equals("text/html")) {
				// If we have text
				if (!StringUtil.isEmpty(indexText)) {
					// Get the metadata blocks from the text
					String[] blocks = indexText.split(Utils.NEWLINE
							+ Utils.NEWLINE);

					// For each line in a block
					for (String lines : blocks) {
						// Get the metadata lines
						String[] metadataLines = lines.split(Utils.NEWLINE);

						if (metadataLines.length >= 2) {
							// Get the author line
							String author = metadataLines[0];

							// Get the title line
							String title = metadataLines[1];

							// Get the affiliation line
							String affiliation = metadataLines.length == 3 ? metadataLines[2]
									: "";

							// If it is a new discovered title
							if (!titleSet.contains(title)) {
								// Scholarly metadata that
								// correspond to individual papers
								ScholarlyMetadata scholarlyMetadata = (ScholarlyMetadata) conferenceMetadata
										.clone();

								// Set the conference URL
								scholarlyMetadata.setConferenceURL(url
										.toString());

								// Set the author
								scholarlyMetadata.setAuthor(author);

								// Set the title
								scholarlyMetadata.setTitle(title);

								// Set the affiliation
								scholarlyMetadata.setAffiliation(affiliation);

								// Add Solr document to Solr documents
								metadata.add(scholarlyMetadata);

								// Add title line to set
								titleSet.add(title);
							}
						}
					}
				}
			} else {
				ScholarlyMetadata scholarlyMetadata = (ScholarlyMetadata) conferenceMetadata
						.clone();

				getMetadataFromParsCit(scholarlyMetadata, indexText);

				// Add Solr document to Solr documents
				metadata.add(scholarlyMetadata);
			}
		}

		// Only index if we have more than one scholarly paper metadata
		if (metadata.size() > 1) {

			XMLWriterSingleton xmlWriter = XMLWriterSingleton.getInstance();

			for (ScholarlyMetadata currentScholarlyMetadata : metadata) {
				if (currentScholarlyMetadata.hasTitle()) {
					xmlWriter.write(currentScholarlyMetadata.toXML());
				}
			}
		}

		return doc;
	}

	public void addIndexBackendOptions(org.apache.hadoop.conf.Configuration conf) {
		LuceneWriter.addFieldOptions("url", LuceneWriter.STORE.YES,
				LuceneWriter.INDEX.TOKENIZED, conf);
	}

	public void setConf(Configuration conf) {
		this.conf = conf;
	}

	public Configuration getConf() {
		return this.conf;
	}
}