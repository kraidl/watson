REQUIRED JARS
lucene-queryparser-7.7.1.jar
lucene-analyzers-common-7.7.1.jar
lucene-core-7.7.1.jar
stanford-corenlp-3.9.2-models.jar
stanford-corenlp-3.9.2-javadoc.jar
stanford-corenlp-3.9.2.jar
stanford-corenlp-3.9.2-sources.jar

NOTE! The wiki data must be in the data folder (found under the resources folder) or this program will not run!
Also, "questions.txt" must be included in the resources folder as well.

In order to run, simply execute Maven. A command prompt will guide you the rest of the way.

Indices do not have to be constructed every time the program is run, but obviously questions cannot be answered
if there is no index.

If an index already exists, then skip this operation.

The system offers the following indexing capabilites -
Good - no stemming, no categories
Better - no stemming, but with categories
Best - stemming AND categories

Good and Better utilize the Standard Analyzer, but can also use the WhiteSpace Analyzer.

The system offers the following search capabilites -
TFIDF or BM25 scoring