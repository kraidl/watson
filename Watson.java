import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.util.CoreMap;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Watson {
	HashSet<String> stopWords;
	StanfordCoreNLP pipeline;

	Configuration configuration;
	ScoringFunction scoringFunction;
	AnalyzerType analyzerType;
	boolean isLemmatized;
	boolean isAdjusted;

	enum Configuration {
		GOOD, BETTER, BEST
	}

	enum ScoringFunction {
		TFIDF, BM25
	}

	enum AnalyzerType {
		ENGLISH, STANDARD, WHITESPACE
	}

	public Watson(ScoringFunction sf, AnalyzerType at, Configuration con, boolean lem, boolean tune) {
		scoringFunction = sf;
		analyzerType = at;
		configuration = con;
		isLemmatized = lem;
		isAdjusted = tune;

		Properties props = null;
		pipeline = null;

		if (isLemmatized) {
			props = new Properties();
			props.put("annotators", "tokenize, ssplit, pos, lemma");
			pipeline = new StanfordCoreNLP(props);
		}

		stopWords = new HashSet<String>(Arrays.asList(stopWordsArray));
	}

	// CORE NLP LEMMATIZARION FUNCTION. ACCEPTS STRING -> ARRAYLIST OF LEMMATIZED
	// WORDS
	public ArrayList<String> lemmatize(String documentText) {
		ArrayList<String> lemmas = new ArrayList<String>();
		// Create an empty Annotation just with the given text
		Annotation document = new Annotation(documentText);
		// run all Annotators on this text
		pipeline.annotate(document);
		// Iterate over all of the sentences found
		List<CoreMap> sentences = document.get(CoreAnnotations.SentencesAnnotation.class);
		for (CoreMap sentence : sentences) {
			// Iterate over all tokens in a sentence
			for (CoreLabel token : sentence.get(CoreAnnotations.TokensAnnotation.class)) {
				// Retrieve and add the lemma for each word into the
				// list of lemmas
				lemmas.add(token.get(CoreAnnotations.LemmaAnnotation.class));
			}
		}
		return lemmas;
	}

	// CONVERT ARRAYLIST OF WORDS TO A STRING
	public String listToString(ArrayList<String> arrayList) {
		StringBuilder sb = new StringBuilder();
		for (String s : arrayList) {
			sb.append(s);
			sb.append(" ");
		}
		sb.append(" ");
		return sb.toString();
	}

	// FUNCTION TO CHECK IF A GIVEN WORD IS A STOP WORD
	public boolean isStopWord(String word) {
		return stopWords.contains(word);
	}

	// FUNCTION TO CHECK IF THE TOPHIT DOC HAS A WORD THAT IS PRESENT IN THE
	// QUERY/QUESTION
	public boolean isPresent(String topHit, String question) {
		topHit = topHit.toLowerCase();
		String[] topHitWords = topHit.split("\\s+");
		String[] questionWords = question.split("\\s+");

		HashSet<String> questionWordsSet = new HashSet<String>(Arrays.asList(questionWords));

		for (String s : topHitWords) {
			if (isStopWord(s))
				continue;
			else if (questionWordsSet.contains(s))
				return true;
		}

		return false;
	}

	// INDEXER FUNCTIONS
	// FUNCTION CALLED WHEN INDEX OPERATION IS CHOSEN
	private void indexData(String indexPath) {
		Date start = new Date();
		try {
			System.out.println("Indexing to: " + indexPath + "/");

			Directory dir = FSDirectory.open(Paths.get(indexPath));

			Analyzer analyzer;
			if (analyzerType == AnalyzerType.ENGLISH)
				analyzer = new EnglishAnalyzer();
			else if (analyzerType == AnalyzerType.WHITESPACE)
				analyzer = new WhitespaceAnalyzer();
			else
				analyzer = new StandardAnalyzer();

			IndexWriterConfig iwc = new IndexWriterConfig(analyzer);

			Path docDir = Paths.get("../");
			iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

			IndexWriter writer = new IndexWriter(dir, iwc);
			indexDocs(writer, docDir);

			writer.close();

			Date end = new Date();
			System.out.println(end.getTime() - start.getTime() + " milliseconds.");

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// FUNCTION TO GET ALL DOCUMENTS PRESENT IN THE FOLDER CONTAINING THE WIKI
	// DOCUMENTS AND INDEX THEM ONE BY ONE
	private void indexDocs(IndexWriter writer, Path docDir) throws IOException {

		File folder = new File("data");

		File[] listOfFiles = folder.listFiles();

		for (File file : listOfFiles)
			if (file.isFile())
				indexFile(writer, file);

	}

	// FUNCTION THAT PARSES THE CONTENT IN EACH DOC AND SENDS TITLE AND CONTENTS FOR
	// INDEXING
	private void indexFile(IndexWriter writer, File file) throws IOException {
		String fileName = file.getName();
		System.out.println("Indexing File " + fileName);

		if (fileName.contains("DS_Store")) {
			return;
		}
		FileInputStream fstream = null;
		try {
			fstream = new FileInputStream("data/" + fileName);
		} catch (Exception e) {
			e.printStackTrace();
		}
		BufferedReader br = new BufferedReader(new InputStreamReader(fstream, StandardCharsets.UTF_8));

		String wikiTitle = "";
		String wikiTitleFinal = "";
		StringBuilder wikiContentFinal = new StringBuilder();
		Boolean wikiContentPrevFinish = false;
		StringBuilder wikiContent = new StringBuilder();
		Boolean firstTime = true;

		String strLine = br.readLine();
		Pattern pattern = Pattern.compile("\\[\\[(.+?)\\]\\]");
		Matcher matcher = null;
		while (strLine != null) {
			if (strLine.contains("CATEGORIES:")) {
				if (configuration == Configuration.GOOD) {
					strLine = br.readLine();
					continue;
				}
			}
			if (strLine.contains("==") || strLine.startsWith("#")) {
				strLine = br.readLine();
				continue;
			}

			matcher = pattern.matcher(strLine);

			if (matcher.find()) {
				wikiContentPrevFinish = true;
				wikiContentFinal = new StringBuilder();
				wikiTitleFinal = matcher.group(1);
				if (wikiContentPrevFinish && !firstTime) {

					addDoc(writer, wikiTitle, wikiContent.toString());
					wikiTitle = matcher.group(1);
					wikiContent = new StringBuilder();
				}
				if (firstTime) {
					firstTime = false;
					wikiTitle = matcher.group(1);
				}
			} else {
				strLine.replaceAll("[\\-\\+!\\.\\^:,]", " ");

				if (isLemmatized) {
					strLine = listToString(lemmatize(strLine));
				}

				wikiContentFinal.append(strLine);
				wikiContentFinal.append(" ");
				wikiContent.append(strLine);
				wikiContent.append(" ");
			}
			strLine = br.readLine();
		}

		addDoc(writer, wikiTitleFinal, wikiContentFinal.toString());
	}

	// FUNCTION TO ADD TITLE AND CONTENTS
	private void addDoc(IndexWriter w, String title, String content) throws IOException {
		Document doc = new Document();
		doc.add(new TextField("contents", content, Field.Store.YES));

		doc.add(new StringField("title", title, Field.Store.YES));
		w.addDocument(doc);
	}

	// SEARCH FUNCTIONS
	// FUNCTION TO SEARCH INDEXED DOCS BASED ON THE QUESTIONS
	private void searchIndex(String indexPath) throws IOException, ParseException {

		String field = "contents";

		IndexReader reader = DirectoryReader.open(FSDirectory.open(Paths.get(indexPath)));

		IndexSearcher searcher = new IndexSearcher(reader);
		if (scoringFunction == ScoringFunction.BM25) {
			if (isAdjusted)
				searcher.setSimilarity(new BM25Similarity(1.5f, 0.1f));
			else
				searcher.setSimilarity(new BM25Similarity());
		}

		Analyzer analyzer;
		if (analyzerType == AnalyzerType.ENGLISH)
			analyzer = new EnglishAnalyzer();
		else if (analyzerType == AnalyzerType.WHITESPACE)
			analyzer = new WhitespaceAnalyzer();
		else
			analyzer = new StandardAnalyzer();

		String file = "questions.txt";

		FileInputStream fstream = new FileInputStream(file);
		BufferedReader br = new BufferedReader(new InputStreamReader(fstream, StandardCharsets.UTF_8));

		String category = "";
		String quest = "";
		String ans = "";
		String result = "";
		int hitCount = 0;
		int lineNumber = 0;
		int totalCount = 0;
		QueryParser parser = new QueryParser(field, analyzer);

		String strLine = br.readLine();
		while (strLine != null) {

			if (lineNumber % 4 == 0) {
				category = strLine;
				lineNumber++;
			} else if (lineNumber % 4 == 1) {
				ArrayList<String> lemmatizedQuest = new ArrayList<String>();
				quest = strLine;
				quest = quest.replaceAll("[\\-\\+!\\.\\^:,]", " ");
				if (configuration == Configuration.BETTER || configuration == Configuration.BEST) {
					quest = quest + " " + (category.replaceAll("[\\-\\+!\\.\\^:,]", " ")).toLowerCase();
				}

				if (isLemmatized) {
					lemmatizedQuest = lemmatize(quest);
					quest = listToString(lemmatizedQuest);
				}

				Query query = parser.parse(quest);
				result = getTopHit(searcher, query, quest);
				lineNumber++;
			} else if (lineNumber % 4 == 2) {
				ans = strLine;
				System.out.println("Expected answer: " + ans);
				System.out.println("Result answer: " + result);
//                System.out.println(result);
				if (result.contains(ans) || ans.contains(result)) {
					System.out.println("CORRECT ANSWER");
					hitCount++;
				}
				totalCount++;
				lineNumber++;
			} else if (lineNumber % 4 == 3) {
				lineNumber++;
				// break;
			}

			strLine = br.readLine();
		}
		System.out.println("Total number of Hits: " + hitCount + " out of " + totalCount);
		br.close();
	}

	// FUNCTION TO GET THE TOPHIT DOCUMENT FOR THE GIVEN QUERY
	public String getTopHit(IndexSearcher searcher, Query query, String quest) throws IOException {

		TopDocs results = searcher.search(query, 200000);
		ScoreDoc[] hits = results.scoreDocs;

		if (hits.length == 0)
			return "NO HITS";

		Document doc;
		for (ScoreDoc sDoc : hits) {
			doc = searcher.doc(sDoc.doc);

			String title = doc.get("title");
			if (title != null) {
				if (configuration == Configuration.BEST) {
					if (quest.contains(title) || title.contains(quest) || quest.indexOf(title) > -1
							|| title.indexOf(quest) > -1 || isPresent(title, quest))
						continue;
					else
						return title;
				} else
					return title;

			} else
				continue;
		}

		return "no match";
	}

	public static void main(String[] args) throws IOException, ParseException {
		ScoringFunction scoringFunction = ScoringFunction.TFIDF;
		AnalyzerType analyzerType = AnalyzerType.STANDARD;
		Configuration configuration = Configuration.GOOD;
		boolean isLemmatized = true;
		boolean isTuned = false;

		Scanner scan = new Scanner(System.in);

		System.out.print("Enter configuration (1 for good, 2 for better, 3 for best): ");
		int ans = Integer.parseInt(scan.nextLine());
		if (ans == 2)
			configuration = Configuration.BETTER;
		else if (ans == 3)
			configuration = Configuration.BEST;

		System.out.println("Configuration: " + configuration);

		System.out.print("Enter scoring function (1 for tfidf, 2 for bm25): ");
		ans = Integer.parseInt(scan.nextLine());
		if (ans == 2)
			scoringFunction = ScoringFunction.BM25;

		System.out.println("Scoring function: " + scoringFunction);

		if (configuration == Configuration.BEST) {
			analyzerType = AnalyzerType.ENGLISH;
		} else {
			System.out.print("Enter analyzer type (1 for standard, 2 for whitespace): ");
			ans = Integer.parseInt(scan.nextLine());
			if (ans == 2)
				analyzerType = AnalyzerType.WHITESPACE;
		}

		System.out.println("Analyzer type: " + analyzerType);

		System.out.print("Should lemmatize terms? (1 for yes, 2 for no): ");
		ans = Integer.parseInt(scan.nextLine());
		if (ans == 2)
			isLemmatized = false;

		System.out.println("isLemmatized: " + isLemmatized);

		if (scoringFunction == ScoringFunction.BM25) {
			System.out.print("Should provide adjusting for BM25? (1 for yes, 2 for no): ");
			ans = Integer.parseInt(scan.nextLine());
			if (ans == 1)
				isTuned = true;

			System.out.println("isTuned: " + isTuned);
		}

		Watson watson = new Watson(scoringFunction, analyzerType, configuration, isLemmatized, isTuned);

		String indexPath = "index" + "_" + analyzerType + "_" + configuration;
		if (isLemmatized)
			indexPath = indexPath + "_LEMMA";
		else
			indexPath = indexPath + "_NOLEMMA";

		System.out.print("Should index data? (1 for yes, 2 for no): ");
		ans = Integer.parseInt(scan.nextLine());
		if (ans == 1)
			watson.indexData(indexPath);

		System.out.println();
		System.out.print("Begin search using questions.txt? (1 for yes, 2 for no): ");
		ans = Integer.parseInt(scan.nextLine());
		if (ans == 1)
			watson.searchIndex(indexPath);

		System.out.println();
		scan.close();
	}

	String[] stopWordsArray = { "a", "about", "above", "after", "again", "against", "all", "am", "an", "and", "any",
			"are", "as", "at", "be", "because", "been", "before", "being", "below", "between", "both", "but", "by",
			"could", "did", "do", "does", "doing", "down", "during", "each", "few", "for", "from", "further", "had",
			"has", "have", "having", "he", "he'd", "he'll", "he's", "her", "here", "here's", "hers", "herself", "him",
			"himself", "his", "how", "how's", "i", "i'd", "i'll", "i'm", "i've", "if", "in", "into", "is", "it", "it's",
			"its", "itself", "let's", "me", "more", "most", "my", "myself", "nor", "of", "on", "once", "only", "or",
			"other", "ought", "our", "ours", "ourselves", "out", "over", "own", "same", "she", "she'd", "she'll",
			"she's", "should", "so", "some", "such", "than", "that", "that's", "the", "their", "theirs", "them",
			"themselves", "then", "there", "there's", "these", "they", "they'd", "they'll", "they're", "they've",
			"this", "those", "through", "to", "too", "under", "until", "up", "very", "was", "we", "we'd", "we'll",
			"we're", "we've", "were", "what", "what's", "when", "when's", "where", "where's", "which", "while", "who",
			"who's", "whom", "why", "why's", "with", "would", "you", "you'd", "you'll", "you're", "you've", "your",
			"yours", "yourself", "yourselves" };
}