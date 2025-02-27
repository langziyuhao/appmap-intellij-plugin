package appland.installGuide.analyzer;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vfs.VirtualFile;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.Map;

/**
 * Finds the main language in a directory tree.
 * This is intended to be short-lived. It shouldn't be cached for a longer time and only be created on-demand.
 */
public class LanguageResolver {
    private final @NotNull ChangeListManager changeListManager;
    private final @NotNull Project project;

    public LanguageResolver(@NotNull Project project) {
        this.changeListManager = ChangeListManager.getInstance(project);
        this.project = project;
    }

    /**
     * Recursively walk the given rootDirectory for known source code files and returns the best-guess SUPPORTED
     * language for the full directory tree.
     * Ignores files and directories that are Git ignored.
     * If the most used language is not supported, returns {@code null}.
     */
    public Languages.Language getLanguage(@NotNull VirtualFile rootDirectory) {
        var detectedLanguages = getLanguageDistribution(rootDirectory);
        var bestLanguage = detectedLanguages.object2DoubleEntrySet()
                .stream()
                .max(Comparator.comparingDouble(Object2DoubleMap.Entry::getDoubleValue));
        return bestLanguage.map(Map.Entry::getKey).orElse(null);
    }

    /**
     * Recursively scans the directory for the contained languages.
     *
     * @param directory The directory to scan
     * @return Map of language to rating, normalized to a range of [0.0, 1.0]
     */
    private @NotNull Object2DoubleMap<Languages.Language> getLanguageDistribution(@NotNull VirtualFile directory) {
        if (isIgnored(directory)) {
            // 2024.1 eap5 uses a customized FastUtil library and trimmed Object2DoubleMaps.emptyMap() from the SDK
            return new Object2DoubleOpenHashMap<>(0);
        }

        var languageCounts = new Object2IntOpenHashMap<Languages.Language>();

        ProjectFileIndex.getInstance(project).iterateContentUnderDirectory(directory,
                fileOrDir -> true,
                fileOrDir -> {
                    if (isIgnored(fileOrDir)) {
                        return false;
                    }

                    if (!fileOrDir.isDirectory()) {
                        var extension = fileOrDir.getExtension();
                        if (extension != null) {
                            var language = Languages.getLanguage(extension);
                            if (language != null) {
                                languageCounts.addTo(language, 1);
                            }
                        }
                    }

                    return true;
                }
        );

        var totalFiles = languageCounts.values().intStream().sum();
        var normalizedMap = new Object2DoubleOpenHashMap<Languages.Language>();
        languageCounts.forEach((language, count) -> normalizedMap.put(language, (double) count / totalFiles));
        return normalizedMap;
    }

    /**
     * @param file File to check
     * @return {@code true} if the file is ignored by the VCS.
     */
    private boolean isIgnored(@NotNull VirtualFile file) {
        return changeListManager.isIgnoredFile(file);
    }
}
