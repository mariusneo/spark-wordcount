package com.samples.spark;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.SparkSession;
import scala.Tuple2;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static com.samples.spark.SerializableComparator.serialize;

/**
 * This class builds on the same algorithm presented within {@link JavaWordCount},
 * but instead of showing the counts of all the words from within the document,
 * it shows only the most frequent words from the file.
 * <p>
 * <p>
 * The stopwords are filtered out from the frequency counting of the words. For this purpose,
 * another file containing the stop words needs to be specified as a parameter for this program.
 *
 * @see JavaWordCount
 */
public class JavaMostFrequentWords {

    private static final Pattern SPACE = Pattern.compile(" ");

    /**
     * Removes punctuation, changes to lower case, and strips leading and trailing spaces.
     * <p>
     * Only spaces, letters, and numbers should be retained.  Other characters should should be
     * eliminated (e.g. it's becomes its).  Leading and trailing spaces should be removed after
     * punctuation is removed.
     *
     * @param s input string
     * @return the input string without punctuation
     */
    public static String removePunctuation(String s) {
        return s.replaceAll("[^a-zA-Z\\d\\s]", "").trim().toLowerCase();
    }

    public static void main(String[] args) throws Exception {

        if (args.length < 3) {
            System.err.println("Usage: JavaMostFrequentWords <file> <stopwords-file> <count>");
            System.exit(1);
        }

        SparkSession spark = SparkSession
                .builder()
                .appName("JavaMostFrequentWords")
                .getOrCreate();

        String filePath = args[0];
        String stopWordsFilePath = args[1];
        int n = Integer.parseInt(args[2]);

        List<String> stopWords = spark.read().textFile(stopWordsFilePath).collectAsList();


        JavaRDD<String> lines = spark.read().textFile(filePath).javaRDD();

        Function<String, Boolean> isNotEmpty = s -> s.trim().length() > 0;

        // Broadcast variables allow the programmer to keep a read-only variable
        // cached on each machine rather than shipping a copy of it with tasks.
        Broadcast<List<String>> stopWordsBroadcast = new JavaSparkContext(spark.sparkContext()).broadcast(stopWords);

        JavaRDD<String> words = lines.filter(isNotEmpty)
                .map(JavaMostFrequentWords::removePunctuation)
                .filter(isNotEmpty)
                .flatMap((FlatMapFunction<String, String>) s -> Arrays.asList(SPACE.split(s)).iterator()).filter
                        (isNotEmpty)
                .filter((Function<String, Boolean>) token -> !stopWordsBroadcast.value().contains(token));


        JavaPairRDD<String, Integer> ones = words.mapToPair(
                (PairFunction<String, String, Integer>) s -> new Tuple2<>(s, 1));

        JavaPairRDD<String, Integer> counts = ones.reduceByKey(
                (Function2<Integer, Integer, Integer>) (i1, i2) -> i1 + i2);


        List<Tuple2<String, Integer>> output = counts.takeOrdered(n,
                serialize((wordCountTuple1, wordCountTuple2) -> -Integer.compare(wordCountTuple1._2(),
                        wordCountTuple2._2())));

        for (Tuple2<?, ?> tuple : output) {
            System.out.println(tuple._1() + ": " + tuple._2());
        }
        spark.stop();
    }
}
