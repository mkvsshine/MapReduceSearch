/**
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */




import java.io.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import java.util.StringTokenizer;


import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;


public class GenIndex {

    public static enum WORD_COUNTER {
        STOP_WORD_COUNT,
        WORD_SUM_COUNT,
    };


  // 1. separate the sentences into words, convert to lower case
  public static class Mapper1_Count
       extends Mapper<Object, Text, Text, IntWritable>{
    
    private final static IntWritable one = new IntWritable(1);
    private Text word = new Text();
      
    public void map(Object key, Text value, Context context
                    ) throws IOException, InterruptedException {
      StringTokenizer itr = new StringTokenizer(value.toString().toLowerCase(), " !,.?:;'()*\t\n\"[]-/<>&#");
      while (itr.hasMoreTokens()) {
        word.set(itr.nextToken());
        context.write(word, one);
      }
    }
  }

  // 2. count the appearance of each words and store the list
  public static class Reducer1_Count
        extends  Reducer<Text, IntWritable, Text, IntWritable> {
      private IntWritable result = new IntWritable();

      public void reduce(Text key, Iterable<IntWritable> values,
                         Context context) throws IOException, InterruptedException {
          int sum = 0;
          for (IntWritable val: values) {
              sum += val.get();
          }

          result.set(sum);
          context.write(key, result);
      }
  }

    // 3. Change the order of the list, sort the words according to the value instead of key
    public static class Mapper2_Sort
            extends Mapper<Object, Text, IntWritable, Text>{

        public static  int WordSum = 0;

        public void map(Object key, Text value, Context context) throws IOException, InterruptedException {
            String line = value.toString();
            StringTokenizer stringTokenizer = new StringTokenizer(line);
            {
                int number = -1;
                String word = "empty";

                if(stringTokenizer.hasMoreTokens())
                {
                    String str0= stringTokenizer.nextToken();
                    word = str0.trim();
                }
                else
                {
                    return;
                }

                if(stringTokenizer.hasMoreElements())
                {
                    String str1 = stringTokenizer.nextToken();
                    number = Integer.parseInt(str1.trim());
                }

                context.write(new IntWritable(number), new Text(word));

                WordSum++;
//                context.getCounter(WORD_COUNTER.WORD_SUM_COUNT).increment(1);
//                System.out.println("WordSumCount=" + context.getCounter(WORD_COUNTER.WORD_SUM_COUNT).getValue());
            }
        }

        @Override
        protected void cleanup(Context context) throws IOException, InterruptedException {
            super.cleanup(context);
//            System.out.println("Clean UP: WordSumCount=" + context.getCounter(WORD_COUNTER.WORD_SUM_COUNT).getValue());
            System.out.println("Clean UP: WordSum=" + WordSum);

            // get  file system and file name for stop words
            FileSystem fs = FileSystem.get(context.getConfiguration());
            String fsName = context.getConfiguration().get("fs.default.name");
            fsName = fsName.concat("/local_scratch/wordcount/stopword/stop-word-count");
            Path stopPath = new Path(fsName);

            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fs.create(stopPath))) ;
//            bw.write("Stop\t"+WordSum + "\n");
            bw.write(String.valueOf(WordSum));

            System.out.println("path=" + stopPath);

            bw.close();
        }
    }


    // 4. invert the order, sort word by frequency from higher to lower
    public static class SortReducerByValuesKeyComparator extends WritableComparator {
        protected SortReducerByValuesKeyComparator() {
            super(IntWritable.class);
        }

        @Override
        public int compare(byte[] b1, int s1, int l1,
                           byte[] b2, int s2, int l2) {

            Integer v1 = ByteBuffer.wrap(b1, s1, l1).getInt();
            Integer v2 = ByteBuffer.wrap(b2, s2, l2).getInt();

            return v1.compareTo(v2) * (-1);
        }
    }

    // 5. store the words in the top 1% of the list into stop words
    public static class Reducer2_Sort
            extends  Reducer<IntWritable, Text, IntWritable, Text> {

        private int WordSum;
        private int StopWordCount;
        private int CurrentWordCount;
        private int StopWordRate;

        // get the total number of words and calculate the count of the stop words
        protected void setup(Context context) throws  IOException{

            // get the total count of the words from the mapper step
//            try {
//                WordSum = Mapper2_Sort.class.getField("WordSum").getInt(null);
//            } catch (NoSuchFieldException e) {
//                System.out.println("err=" + e.toString());
//            } catch (IllegalAccessException e) {
//                System.out.println("err=" + e.toString());
//            }

            FileSystem fs = FileSystem.get(context.getConfiguration());
            String fsName = context.getConfiguration().get("fs.default.name");
            fsName = fsName.concat("/local_scratch/wordcount/stopword/stop-word-count");
            Path stopPath = new Path(fsName);
            BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(stopPath)));

            WordSum = Integer.parseInt(br.readLine());

            // number of stop words
            StopWordRate = 1;
            StopWordCount = WordSum * StopWordRate / 100;
            CurrentWordCount = 0;
            System.out.println("Total:" + WordSum + ", Stop:" + StopWordCount);

//            Counter sumCounter = context.getCounter(WORD_COUNTER.WORD_SUM_COUNT);
//            System.out.println("cnt=" + sumCounter.getValue() + " counter="+sumCounter);
        }

        public void reduce(IntWritable key, Iterable<Text> values,
                           Context context) throws IOException, InterruptedException {

            for (Text val: values) {
                if (CurrentWordCount ++ < StopWordCount) {
                    context.write(key, val);
                }
                else {
                    return;
                }
            }
        }
    }


    // 6.
    public static class Mapper3_index
            extends Mapper<Object, Text, Text, Text>{

        private ArrayList<String> stopWords;
        private String fileName;
        private int lineNumber = 0;
        private Text word = new Text();
        private Text index = new Text();

        protected void setup(Context context) throws IOException, InterruptedException {

            stopWords = new ArrayList<String>();

            // get  file system and file name for stop words
            FileSystem fs = FileSystem.get(context.getConfiguration());
            String fsName = context.getConfiguration().get("fs.default.name");
            fsName = fsName.concat("/local_scratch/wordcount/stopword/part-r-00000");
            Path stopPath = new Path(fsName);
            BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(stopPath)));

            try {
                StringBuilder sb = new StringBuilder();
                String line = br.readLine();
                String sWord;

                while (line != null) {

                    StringTokenizer stringTokenizer = new StringTokenizer(line);
                    stringTokenizer.nextToken();
                    sWord = stringTokenizer.nextToken();
                    stopWords.add(sWord);

                    line = br.readLine();
                }

            }
            finally {
                br.close();
            }

            // get the file name and initiate the line number
            FileSplit fileSplit = (FileSplit) context.getInputSplit();
            fileName = fileSplit.getPath().getName();
        }

        protected void map(Object key, Text value, Context context) throws IOException, InterruptedException {
            StringTokenizer itr = new StringTokenizer(value.toString().toLowerCase(), " !,.?:;'()*\t\n\"[]-/<>&#");

           String rWord;
           String rIndex;
           int position = 0;


            while (itr.hasMoreTokens()) {

                position ++;
                rWord = itr.nextToken();

                // ignore the stop words
                if (stopWords.contains(rWord)) {
//                    System.out.println("Stopword:" + rWord);
                    continue;
                }

                word.set(rWord);
                rIndex = fileName + ","+lineNumber + "," +position;
                index.set(rIndex);

                context.write(word, index);
             }
            lineNumber ++;
        }
    }

    public static class Reducer3_index
            extends  Reducer<Text, Text, Text, Text> {

        private Text fullIndex = new Text();

        public void reduce(Text key, Iterable<Text> values,
                           Context context) throws IOException, InterruptedException {
            StringBuilder rFullIndex = new StringBuilder();

            for (Text val: values) {
                rFullIndex.append(val);
                rFullIndex.append(";");
//                System.out.println("val="+val);
            }
//            System.out.println("values="+values.toString());

            fullIndex.set(String.valueOf(rFullIndex));
            context.write(key, fullIndex);
        }
    }


  public static void main(String[] args) throws Exception {
    Configuration conf = new Configuration();

    String[] otherArgs = new GenericOptionsParser(conf, args).getRemainingArgs();
    if (otherArgs.length != 2) {
      System.err.println("Usage: wordcount <in> <out>");
      System.exit(2);
    }
    String tmpPath = "/local_scratch/wordcount/tmp";
    String stopWord = "/local_scratch/wordcount/stopword";

    // Job to count the words
    Job count_job = new Job(conf, "word count");
    count_job.setJarByClass(GenIndex.class);
    count_job.setMapperClass(Mapper1_Count.class);
    count_job.setCombinerClass(Reducer1_Count.class);
    count_job.setReducerClass(Reducer1_Count.class);

    count_job.setOutputKeyClass(Text.class);
    count_job.setOutputValueClass(IntWritable.class);

    FileInputFormat.addInputPath(count_job, new Path(otherArgs[0]));
    FileOutputFormat.setOutputPath(count_job, new Path(tmpPath));
    count_job.waitForCompletion(true);

      Job sort_job = new Job(conf, "word sort");
      sort_job.setJarByClass(GenIndex.class);
      sort_job.setMapperClass(Mapper2_Sort.class);
      sort_job.setCombinerClass(Reducer2_Sort.class);
      sort_job.setReducerClass(Reducer2_Sort.class);
      sort_job.setSortComparatorClass(SortReducerByValuesKeyComparator.class);
      sort_job.setOutputKeyClass(IntWritable.class);
      sort_job.setOutputValueClass(Text.class);


      FileInputFormat.addInputPath(sort_job, new Path(tmpPath));
      FileOutputFormat.setOutputPath(sort_job, new Path(stopWord));

      sort_job.waitForCompletion(true);

      // job to generate the index
      Job index_job = new Job(conf, "word index");
      index_job.setJarByClass(GenIndex.class);
      index_job.setMapperClass(Mapper3_index.class);
      index_job.setCombinerClass(Reducer3_index.class);
      index_job.setReducerClass(Reducer3_index.class);

      index_job.setOutputKeyClass(Text.class);
      index_job.setOutputValueClass(Text.class);

      FileInputFormat.addInputPath(index_job, new Path(otherArgs[0]));
      FileOutputFormat.setOutputPath(index_job, new Path(otherArgs[1]));

      index_job.waitForCompletion(true);

      System.exit(0);
  }
}
















