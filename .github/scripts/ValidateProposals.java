///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ValidateProposals {

    static final Pattern PROPOSAL_FILE_PATTERN = Pattern.compile("\\d{3}-.+\\.md");
    static final Pattern NAMING_CONVENTION = Pattern.compile("\\d{3}-[a-z][a-z0-9]*(-[a-z0-9]+)*\\.md");
    static final Pattern README_TABLE_ROW = Pattern.compile(
            "^\\|\\s*(\\d{3})\\s*\\|\\s*\\[.*?\\]\\(\\./([^)]+)\\).*$");
    static final Set<String> EXCLUDED_FILES = Set.of("README.md", "000-template.md");

    public static void main(String[] args) throws Exception {
        List<String> errors = new ArrayList<>();

        List<String> proposals = getProposalFiles();

        // Rule 1: Filename format
        List<String> validProposals = new ArrayList<>();
        for (String file : proposals) {
            if (matchesNamingConvention(file)) {
                validProposals.add(file);
            } else {
                errors.add("Filename '" + file + "' does not match the required pattern: "
                        + "NNN-kebab-case-title.md (3-digit prefix, lowercase kebab-case)");
            }
        }

        // Rule 2: README table entry
        Map<Integer, String> readmeEntries = parseReadmeEntries(Path.of("README.md"));
        for (String file : validProposals) {
            int number = extractProposalNumber(file);
            validateReadmeEntry(number, file, readmeEntries, errors);
        }

        // Rule 3: Sequential numbering (check duplicates first, then gaps)
        Map<Integer, List<String>> proposalsByNumber = new HashMap<>();
        for (String file : validProposals) {
            int number = extractProposalNumber(file);
            proposalsByNumber.computeIfAbsent(number, k -> new ArrayList<>()).add(file);
        }

        for (var entry : proposalsByNumber.entrySet()) {
            if (entry.getValue().size() > 1) {
                errors.add("Proposal number " + String.format("%03d", entry.getKey())
                        + " is used by " + entry.getValue().size() + " files: "
                        + String.join(", ", entry.getValue()));
            }
        }

        List<Integer> distinctNumbers = proposalsByNumber.keySet().stream()
                .sorted()
                .collect(Collectors.toList());

        int expected = 1;
        for (int num : distinctNumbers) {
            if (num != expected) {
                errors.add("Proposal number " + String.format("%03d", num)
                        + " is not sequential. Expected " + String.format("%03d", expected)
                        + " (no gaps allowed)");
            }
            expected = num + 1;
        }

        if (!errors.isEmpty()) {
            System.err.println("Proposal validation failed with " + errors.size() + " error(s):");
            for (String error : errors) {
                System.err.println("  - " + error);
            }
            System.exit(1);
        }

        System.out.println("All proposal validations passed.");
    }

    static List<String> getProposalFiles() throws IOException {
        try (var stream = Files.list(Path.of("."))) {
            return stream
                    .map(p -> p.getFileName().toString())
                    .filter(f -> f.endsWith(".md"))
                    .filter(f -> !EXCLUDED_FILES.contains(f))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    static boolean matchesNamingConvention(String filename) {
        return NAMING_CONVENTION.matcher(filename).matches();
    }

    static int extractProposalNumber(String filename) {
        if (filename.length() >= 3) {
            try {
                return Integer.parseInt(filename.substring(0, 3));
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return -1;
    }

    static Map<Integer, String> parseReadmeEntries(Path readmePath) throws IOException {
        Map<Integer, String> entries = new HashMap<>();
        List<String> lines = Files.readAllLines(readmePath);
        for (String line : lines) {
            Matcher m = README_TABLE_ROW.matcher(line.trim());
            if (m.matches()) {
                int number = Integer.parseInt(m.group(1));
                String linkedFile = m.group(2);
                entries.put(number, linkedFile);
            }
        }
        return entries;
    }

    static void validateReadmeEntry(int number, String filename,
                                    Map<Integer, String> readmeEntries, List<String> errors) {
        String linkedFile = readmeEntries.get(number);
        if (linkedFile == null) {
            errors.add("README.md is missing a table entry for proposal "
                    + String.format("%03d", number) + " (" + filename + ")");
        } else if (!linkedFile.equals(filename)) {
            errors.add("README.md entry for proposal " + String.format("%03d", number)
                    + " links to '" + linkedFile + "' but expected '" + filename + "'");
        }
    }
}
