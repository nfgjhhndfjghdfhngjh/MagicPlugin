package com.elmakers.mine.bukkit.utility.help;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;

import org.apache.commons.lang.StringUtils;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import com.elmakers.mine.bukkit.ChatUtils;
import com.elmakers.mine.bukkit.api.magic.Mage;
import com.elmakers.mine.bukkit.utility.ConfigurationUtils;
import com.elmakers.mine.bukkit.utility.Messages;

public class Help {
    private static double DEFAULT_WEIGHT = 0.00001;
    private static final double RARITY_FACTOR = 3;
    private static final double TOPIC_RARITY_FACTOR = 5;
    private static final double LENGTH_FACTOR = 0.2;
    private final Messages messages;
    private final Map<String, HelpTopic> topics = new HashMap<>();
    private final Map<String, HelpTopicWord> words = new HashMap<>();
    private final Map<String, String> metaTemplates = new HashMap<>();
    // We cheat and use one regex for both <li> and <link ...>
    private static final Pattern linkPattern = Pattern.compile("([^`])(<li[^>]*>)([^`])");
    private int maxCount = 0;
    private int maxTopicCount = 0;
    private int maxLength = 0;

    public Help(Messages messages) {
        this.messages = messages;
    }

    public void reset() {
        maxCount = 0;
        maxTopicCount = 0;
        maxLength = 0;
        topics.clear();
        words.clear();
        metaTemplates.clear();
    }

    public void load(ConfigurationSection helpSection, ConfigurationSection examplesSection, ConfigurationSection metaSection) {
        loadMetaTemplates(metaSection);
        if (helpSection != null) {
            ConfigurationSection helpExamples = helpSection.getConfigurationSection("examples");
            if (helpExamples != null) {
                ConfigurationUtils.addConfigurations(helpExamples, examplesSection);
            } else {
                helpSection.set("examples", examplesSection);
            }
            load(helpSection);
        }
    }

    public void load(ConfigurationSection helpSection) {
        Collection<String> keys = helpSection.getKeys(true);
        for (String key : keys) {
            if (helpSection.isConfigurationSection(key)) continue;
            String value = helpSection.getString(key);
            loadTopic(key, value);
        }
    }

    public void loadTopic(String key, String contents) {
        loadTopic(key, contents, "", "");
    }

    public void loadTopic(String key, String contents, String tags, String topicType) {
        HelpTopic helpTopic = new HelpTopic(messages, key, contents, tags, topicType);
        topics.put(key, helpTopic);
        // Index all words
        Map<String, Integer> wordCounts = helpTopic.getWordCounts();
        for (Map.Entry<String, Integer> wordEntry : wordCounts.entrySet()) {
            String word = wordEntry.getKey();
            HelpTopicWord wordCount = words.get(word);
            if (wordCount == null) {
                wordCount = new HelpTopicWord();
                words.put(word, wordCount);
            }
            wordCount.addTopic(wordEntry.getValue());
            maxCount = Math.max(maxCount, wordCount.getCount());
            maxTopicCount = Math.max(maxTopicCount, wordCount.getTopicCount());
            maxLength = Math.max(maxLength, word.length());
        }
    }

    private void loadMetaTemplates(ConfigurationSection metaSection) {
        if (metaSection == null) return;
        for (String key : metaSection.getKeys(true)) {
            metaTemplates.put(key, metaSection.getString(key));
        }
    }

    public void loadMetaActions(Map<String, Map<String, Object>> actions) {
        loadMetaClassed(actions, "action", "action spell", "reference");
    }

    public void loadMetaEffects(Map<String, Map<String, Object>> effects) {
        loadMetaClassed(effects, "effect", "effectlib spell", "reference");
    }

    private String convertMetaDescription(String description) {
        return linkPattern.matcher(description).replaceAll("$1`$2`$3");
    }

    @SuppressWarnings("unchecked")
    private void loadMetaClassed(Map<String, Map<String, Object>> meta, String metaType, String tags, String topicType) {
        String descriptionTemplate = metaTemplates.get(metaType + "_template");
        String defaultDescription = metaTemplates.get("default_description");
        String defaultParameterDescription = metaTemplates.get("default_parameter_description");
        String parameterTemplate = metaTemplates.get("parameter_template");
        String parameterExtraLineTemplate = metaTemplates.get("parameter_extra_line");
        String parametersTemplate = metaTemplates.get("parameters_template");
        String examplesTemplate = metaTemplates.get("examples_template");
        String exampleTemplate = metaTemplates.get("example_template");
        for (Map.Entry<String, Map<String, Object>> entry : meta.entrySet()) {
            Map<String, Object> action = entry.getValue();
            String key = entry.getKey();
            String shortClass = (String)action.get("short_class");
            if (shortClass == null) continue;
            List<String> descriptionList = (List<String>)action.get("description");
            String description = StringUtils.join(descriptionList, "\n");
            if (description.isEmpty()) {
                description = defaultDescription;
            }
            description = descriptionTemplate.replace("$class", shortClass)
                .replace("$description", description)
                .replace("$key", key);
            String metaCategory = (String)action.get("category");
            if (metaCategory != null && !metaCategory.isEmpty()) {
                tags += " " + metaCategory;
            }
            Object rawExamples = action.get("examples");
            if (rawExamples != null && rawExamples instanceof List) {
                List<String> exampleList = (List<String>)rawExamples;
                for (int i = 0; i < exampleList.size(); i++) {
                    exampleList.set(i, exampleTemplate.replace("$example", exampleList.get(i)));
                }
                description += "\n" + examplesTemplate.replace("$examples", StringUtils.join(exampleList, " "));
            }
            Object rawParameters = action.get("parameters");
            // The conversion process turns empty maps into empty lists
            if (rawParameters != null && rawParameters instanceof Map) {
                Map<String, Object> parameters = (Map<String, Object>)rawParameters;
                if (parameters != null && !parameters.isEmpty()) {
                    List<String> parameterLines = new ArrayList<>();
                    for (Map.Entry<String, Object> parameterEntry : parameters.entrySet()) {
                        String parameterDescription = parameterEntry.getValue().toString();
                        if (parameterDescription.isEmpty()) {
                            parameterDescription = defaultParameterDescription;
                        }
                        // This is delimited by an escaped \n
                        String[] descriptionLines = parameterDescription.split("\\\\n");
                        parameterLines.add(parameterTemplate
                                .replace("$parameter", parameterEntry.getKey())
                                .replace("$description", descriptionLines[0])
                        );
                        for (int i = 1; i < descriptionLines.length; i++) {
                            parameterLines.add(parameterExtraLineTemplate
                                    .replace("$parameter", parameterEntry.getKey())
                                    .replace("$description", descriptionLines[i])
                            );
                        }
                    }
                    description += "\n" + parametersTemplate.replace("$parameters", StringUtils.join(parameterLines, "\n"));
                }
            }

            description = convertMetaDescription(description);
            // hacky plural here, be warned
            loadTopic("reference." + metaType + "s." + key, description, tags, topicType);
        }
    }

    public Set<String> getWords() {
        return words.keySet();
    }

    public boolean isWord(String word) {
        return words.containsKey(word);
    }

    public double getRarityWeight(String word) {
        HelpTopicWord wordCount = words.get(word);
        if (wordCount == null) return DEFAULT_WEIGHT;
        return getRarityWeight(wordCount);
    }

    protected double getRarityWeight(HelpTopicWord wordCount) {
        if (wordCount == null) return DEFAULT_WEIGHT;
        double rarityWeight = 1.0 - ((double)wordCount.getCount() / (maxCount + 1));
        return Math.pow(rarityWeight, RARITY_FACTOR);
    }

    public double getLengthWeight(String word) {
        double lengthWeight = (double)word.length() / maxLength;
        return Math.pow(lengthWeight, LENGTH_FACTOR);
    }

    public double getTopicWeight(String word) {
        HelpTopicWord wordCount = words.get(word);
        return getTopicWeight(wordCount);
    }

    protected double getTopicWeight(HelpTopicWord wordCount) {
        if (wordCount == null) return DEFAULT_WEIGHT;
        double topicRarityWeight = 1.0 - ((double)wordCount.getTopicCount() / (maxTopicCount + 1));
        return Math.pow(topicRarityWeight, TOPIC_RARITY_FACTOR);
    }

    public double getWeight(String word) {
        if (maxCount == 0 || maxLength == 0 || maxTopicCount == 0) return DEFAULT_WEIGHT;

        HelpTopicWord wordCount = words.get(word);
        double rarityWeight = getRarityWeight(wordCount);
        double topicRarityWeight = getTopicWeight(wordCount);
        double lengthWeight = getLengthWeight(word);
        return  rarityWeight * topicRarityWeight * lengthWeight;
    }

    public Set<String> getTopicKeys() {
        return topics.keySet();
    }

    public boolean showTopic(Mage mage, String key) {
        HelpTopic topic = topics.get(key);
        if (topic == null) {
            return false;
        }
        mage.sendMessage(topic.getText());
        return true;
    }

    public HelpTopic getTopic(String key) {
        return topics.get(key);
    }

    @Nonnull
    public List<HelpTopicMatch> findMatches(List<String> keywords, int listLength) {
        List<HelpTopicMatch> matches = new ArrayList<>();
        for (HelpTopic topic : topics.values()) {
            double relevance = topic.match(this, keywords);
            if (relevance > 0) {
                matches.add(new HelpTopicMatch(topic, relevance));
            }
        }

        // Group by topic type, make sure to show at least one topic of each type
        Map<String, Queue<HelpTopicMatch>> grouped = new HashMap<>();
        for (HelpTopicMatch match : matches) {
            String topicType = match.getTopic().getTopicType();
            Queue<HelpTopicMatch> groupedList = grouped.get(topicType);
            if (groupedList == null) {
                groupedList = new PriorityQueue<>();
                grouped.put(topicType, groupedList);
            }
            groupedList.add(match);
        }

        // Merge each list in
        matches.clear();
        List<HelpTopicMatch> batch = new ArrayList<>();
        Set<String> addedThisPage = new HashSet<>();
        int thisPageCount = 0;
        while (!grouped.isEmpty()) {
            Iterator<Map.Entry<String, Queue<HelpTopicMatch>>> it = grouped.entrySet().iterator();
            if (grouped.size() == 1) {
                matches.addAll(it.next().getValue());
                break;
            }

            String bestMatchType = null;
            HelpTopicMatch bestMatch = null;
            while (it.hasNext()) {
                Map.Entry<String, Queue<HelpTopicMatch>> entry = it.next();
                Queue<HelpTopicMatch> typeMatches = entry.getValue();
                if (typeMatches.isEmpty()) {
                    it.remove();
                    continue;
                }
                HelpTopicMatch match = typeMatches.peek();
                if (bestMatch == null || match.getRelevance() > bestMatch.getRelevance()) {
                    bestMatch = match;
                    bestMatchType = entry.getKey();
                }
            }
            if (bestMatch == null) break;

            grouped.get(bestMatchType).remove();
            addedThisPage.add(bestMatchType);
            batch.add(bestMatch);
            thisPageCount++;

            // See if we should add at least one entry from each type we have not yet added
            int haveNotAdded = grouped.size() - addedThisPage.size();
            // Check for end of page, each page is sorted
            if (thisPageCount >= listLength - haveNotAdded) {
                if (haveNotAdded > 0) {
                    for (Map.Entry<String, Queue<HelpTopicMatch>> entry : grouped.entrySet()) {
                        if (!addedThisPage.contains(entry.getKey())) {
                            batch.add(entry.getValue().remove());
                        }
                    }
                }
                Collections.sort(batch);
                matches.addAll(batch);

                // Reset state for next page
                batch.clear();
                addedThisPage.clear();;
                thisPageCount = 0;
            }
        }
        // Add anything remaining
        Collections.sort(batch);
        matches.addAll(batch);

        return matches;
    }

    public void search(Mage mage, String[] args, int maxTopics) {
        // This may seem roundabout, but handles punctuation nicely
        List<String> keywords = Arrays.asList(ChatUtils.getWords(StringUtils.join(args, " ").toLowerCase()));
        List<HelpTopicMatch> matches = findMatches(keywords, maxTopics);

        // This is called async, move back to the main thread to do messaging
        ShowTopicsTask showTask = new ShowTopicsTask(this, mage, keywords, matches, maxTopics);
        Plugin plugin = mage.getController().getPlugin();
        plugin.getServer().getScheduler().runTask(plugin, showTask);
    }
}
