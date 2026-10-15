package cn.edu.pdfworkbench.ai;

import static cn.edu.pdfworkbench.ai.AiTypes.*;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.springframework.stereotype.Service;

@Service
public class AiRetrieval {
  private final AiRepository repository;
  private final AiModelClient models;

  public AiRetrieval(AiRepository repository, AiModelClient models) {
    this.repository = repository;
    this.models = models;
  }

  public void index(String id, List<Chunk> chunks, Consumer<String> progress) throws IOException {
    try (var dir = FSDirectory.open(repository.indexPath(id));
        var analyzer = new SmartChineseAnalyzer();
        var writer =
            new IndexWriter(
                dir,
                new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE))) {
      try {
        for (int start = 0; start < chunks.size(); start += 8) {
          interrupted();
          var batch = chunks.subList(start, Math.min(start + 8, chunks.size()));
          var vectors = models.embed(batch.stream().map(Chunk::text).toList());
          if (vectors.size() != batch.size()) throw new IOException("Invalid embedding count");
          for (int i = 0; i < batch.size(); i++) {
            float[] vector = vectors.get(i);
            if (vector.length != 1024) throw new IOException("Invalid embedding dimension");
            var d = new org.apache.lucene.document.Document();
            d.add(new StringField("id", batch.get(i).id(), Field.Store.YES));
            d.add(new TextField("text", batch.get(i).text(), Field.Store.NO));
            d.add(new KnnFloatVectorField("vector", vector, VectorSimilarityFunction.COSINE));
            writer.addDocument(d);
          }
          progress.accept("建立检索索引 " + Math.min(start + 8, chunks.size()) + " / " + chunks.size());
        }
        interrupted();
        writer.commit();
      } catch (Exception e) {
        writer.rollback();
        throw e;
      }
    }
  }

  public List<Chunk> search(String documentId, String question, List<Chunk> allowed)
      throws IOException {
    if (allowed.isEmpty()) return List.of();
    var vector = models.embed(List.of(question)).getFirst();
    try (var dir = FSDirectory.open(repository.indexPath(documentId));
        var reader = DirectoryReader.open(dir);
        var analyzer = new SmartChineseAnalyzer()) {
      var searcher = new IndexSearcher(reader);
      var filter =
          new TermInSetQuery("id", allowed.stream().map(c -> new BytesRef(c.id())).toList());
      var terms = new BooleanQuery.Builder();
      try (var stream = analyzer.tokenStream("text", question)) {
        var term = stream.addAttribute(CharTermAttribute.class);
        stream.reset();
        int count = 0;
        while (stream.incrementToken() && count++ < 128)
          terms.add(new TermQuery(new Term("text", term.toString())), BooleanClause.Occur.SHOULD);
        stream.end();
      }
      var lexical =
          new BooleanQuery.Builder()
              .add(filter, BooleanClause.Occur.FILTER)
              .add(terms.build(), BooleanClause.Occur.MUST)
              .build();
      var ranks = new HashMap<String, Double>();
      for (var query : List.of(lexical, new KnnFloatVectorQuery("vector", vector, 20, filter))) {
        var hits = searcher.search(query, 20).scoreDocs;
        for (int i = 0; i < hits.length; i++) {
          String id = searcher.storedFields().document(hits[i].doc).get("id");
          ranks.merge(id, 1.0 / (60 + i + 1), Double::sum);
        }
      }
      var byId = new HashMap<String, Chunk>();
      allowed.forEach(c -> byId.put(c.id(), c));
      var candidates =
          ranks.entrySet().stream()
              .sorted(
                  Map.Entry.<String, Double>comparingByValue()
                      .reversed()
                      .thenComparing(Map.Entry::getKey))
              .limit(30)
              .map(e -> byId.get(e.getKey()))
              .toList();
      return models.rerank(question, candidates.stream().map(Chunk::text).toList()).stream()
          .map(candidates::get)
          .toList();
    }
  }

  private void interrupted() {
    if (Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException();
  }
}
