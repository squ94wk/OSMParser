import java.io.*;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Parser {
  private static final double[][] bounds = new double[2][2];
  private static final Set<Long> nodeSet = new HashSet<>(100000);
  private static final Set<Long> waySet = new HashSet<>(10000);
  private static final Set<Long> relSet = new HashSet<>(10000);

  private static final Set<String> nativeTags = Set.of("?xml", "osm", "/osm", "bounds");
  private static final Set<String> topLevelTags = Set.of("node", "way", "relation");
  private static final Set<String> whitelistInnerTags = Set.of("tag", "nd");

  private static long lineCounter = 0;
  private static long nodeCounter = 0;

  public static void main(String[] args) {
    if (args.length != 5) {
      System.out.println("Wrong number of arguments. Four expected.");
      System.exit(-1);
    }

    final String fileName = args[0];
    final File inputFile;

    inputFile = new File(fileName);
    if (inputFile.length() == 0) {
      System.out.println("No such file.");
      System.exit(-1);
    }

    if (Objects.equals(fileName, "output.osm")) {
      System.out.println("Please provide another file name.");
      System.exit(-1);
    }

    try {
      for (int i = 0; i < 2; i++) {
        bounds[i][0] = Double.parseDouble(args[2 * i + 1]);
        bounds[i][1] = Double.parseDouble(args[2 * i + 2]);
      }
    } catch (NumberFormatException e) {
      System.out.println("Wrong format of arguments. Doubles expected.");
      System.exit(-1);
    }

    final BufferedReader reader;
    try {
      final FileReader fileReader = new FileReader(inputFile);
      reader = new BufferedReader(fileReader);

      final BufferedWriter writer = new BufferedWriter(new FileWriter("output.osm"));

      boolean stop;
      do {
        stop = !processSentence(reader, writer);
      } while (!stop);

      //end logging
      System.out.println();
      System.out.println("Finished");
      System.out.println(String.format("After %s lines: %s nodes, %s ways kept, %s relations " +
          "formed", lineCounter, nodeCounter, waySet.size(), relSet.size()));
    } catch (FileNotFoundException e) {
      System.out.println("No such file: " + inputFile.getName());
      System.exit(-1);
    } catch (Exception e) {
      e.printStackTrace();
      System.out.println("Can't read file: " + inputFile.getName());
      System.exit(-1);
    }
  }

  private static boolean processSentence(final BufferedReader reader, final BufferedWriter writer)
      throws Exception {
    final StringBuilder builder = new StringBuilder("");
    String read;
    String topLevel = "";
    final Stack<String> tags = new Stack<>();
    while ((read = reader.readLine()) != null) {
      //Logging
      if (lineCounter % 250000 == 0) {
        System.out.println(String.format("After %s lines: %s nodes, %s ways kept, %s relations " +
                "formed", lineCounter, nodeCounter, waySet.size(), relSet.size()));
      }
      lineCounter++;

      final String tag = openingTag(read, tags);
      if (tags.empty()) {
        //just content from previous tag
        builder.append(read).append("\r\n");
      } else {
        if (tag.isEmpty()) {
          builder.append(read).append("\r\n");
        } else if (nativeTags.contains(tag)) {
          builder.append(read).append("\r\n");
          topLevel = tag;
          tags.clear();
          break;
        } else if (topLevelTags.contains(tag)) {
          // always add top level tag
          topLevel = tag;
          builder.append(read).append("\r\n");
        } else { // inside top level
          if (shouldAdd(read, tag)) {
            builder.append(read).append("\r\n");
          }
        }
      }
      closingTag(read, tags);
      if (tags.empty()) {
        break;
      }
      // throw new Exception("Wrong OSM format.");
    }

    final String sentence = builder.toString();

    if (sentence.isEmpty() || !tags.empty()) {
      return false;
    }

    if (shouldKeep(sentence, topLevel)) {
      writer.write(sentence);
      writer.flush();
    }

    return true;
  }

  private static boolean shouldCopyNode(final String read) {
    final String xStr;
    final String yStr;

    final Matcher matcher = Pattern.compile("(lon|lat)=\"-?\\d+\\.?\\d*").matcher(read);
    yStr = matcher.find() ? matcher.group() : "nan";
    xStr = matcher.find() ? matcher.group() : "nan";
    final double x = Double.parseDouble(xStr.substring(5, xStr.length()));
    final double y = Double.parseDouble(yStr.substring(5, yStr.length()));

    // inside bounds
    final boolean copy =
        bounds[0][0] <= x && x <= bounds[1][0] && bounds[0][1] <= y && y <= bounds[1][1];
    // add node to map
    if (copy) {
      final Matcher idMatcher = Pattern.compile("id=\"\\d+").matcher(read);
      final long id = Long.parseLong(idMatcher.find() ? idMatcher.group().substring(4, idMatcher
          .group().length()) : "0");
      nodeSet.add(id);
      nodeCounter++;
    }
    return copy;
  }

  private static boolean shouldCopyWay(final String sentence) {
    final Matcher refMatcher = Pattern.compile("ref=\"\\d+").matcher(sentence);
    while (refMatcher.find()) {
      final String group = refMatcher.group();
      final long id = Long.parseLong(group.substring(5, group.length()));
      if (!nodeSet.contains(id)) {
        return false;
      }
    }

    //keep way
    final Matcher idMatcher = Pattern.compile("id=\"\\d+").matcher(sentence);
    final long wayId = Long.parseLong(idMatcher.find() ? idMatcher.group().substring(4, idMatcher
        .group().length()) : "");
    waySet.add(wayId);
    return true;
  }

  private static boolean shouldCopyRelation(final String sentence) {
    // check if it has any members, otherwise its empty and doesn't need to be kept
    final Matcher refMatcher = Pattern.compile("<member.*>").matcher(sentence);
    if (!refMatcher.find()) {
      return false;
    }

    //keep relation
    final Matcher idMatcher = Pattern.compile("id=\"\\d+").matcher(sentence);
    final long relId = Long.parseLong(idMatcher.find() ? idMatcher.group().substring(4, idMatcher
        .group().length()) : "0");
    relSet.add(relId);
    return true;
  }

  private static boolean shouldAdd(final String line, final String tag) {
    if (whitelistInnerTags.contains(tag)) {
      return true;
    } else if ("member".equals(tag)) {
      return shouldAddMember(line);
    } else {
      System.out.println("non whitelist");
      return false;
    }
  }

  private static boolean shouldAddMember(final String read) {
    final Matcher refMatcher = Pattern.compile("ref=\"\\d+").matcher(read);
    final String wayIdStr = refMatcher.find() ? refMatcher.group() : "";
    if (!wayIdStr.isEmpty()) {
      final long wayId = Long.parseLong(wayIdStr.substring(5, wayIdStr.length()));
      return waySet.contains(wayId);
    } else {
      return false;
    }
  }

  private static boolean shouldKeep(final String sentence, final String tag) {
    if (tag.isEmpty() || nativeTags.contains(tag)) {
      return true;
    }
    if ("node".equals(tag)) {
      return shouldCopyNode(sentence);
    } else if ("way".equals(tag)) {
      return shouldCopyWay(sentence);
    } else if ("relation".equals(tag)) {
      return shouldCopyRelation(sentence);
    }

    //neither node, way or relation: shouldn't be accessible
    return true;
  }

  private static String openingTag(final String line, final Stack<String> tags) {
    final Matcher tagMatcher = Pattern.compile("\\s*<\\??\\w+ ").matcher(line);
    if (!tagMatcher.find()) {
      return "";
    }

    final String group = tagMatcher.group();
    final String tag = group.substring(group.indexOf('<') + 1, group.length() - 1);
    tags.push(tag);
    return tag;
  }

  private static boolean closingTag(final String line, final Stack<String> tags) {
    if (tags.empty()) {
      return false;
    }
    final String tag = tags.peek();
    if (!line.endsWith("/>") && !line.endsWith("</" + tag + ">")) {
      return false;
    }
    tags.pop();
    return true;
  }
}
