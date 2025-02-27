package appland.index;

import appland.problemsView.model.TestStatus;
import com.intellij.openapi.vfs.VirtualFile;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

/**
 * Contains metadata about a single AppMap.
 */
@Getter
@EqualsAndHashCode
@ToString
public final class AppMapMetadata {
    /**
     * The name defined by the metadata.
     */
    private final @NotNull String name;
    private final @Nullable TestStatus testStatus;
    private final @Nullable String languageName;
    private final @Nullable String recorderType;
    private final @Nullable String recorderName;
    private final int requestCount;
    private final int queryCount;
    private final int functionsCount;
    private final @Nullable VirtualFile appMapFile;

    @TestOnly
    public AppMapMetadata(@NotNull String name, @Nullable VirtualFile appMapFile) {
        this(name, appMapFile, null, null, null, null, 0, 0, 0);
    }

    public AppMapMetadata(@NotNull String name,
                          @Nullable VirtualFile appMapFile,
                          @Nullable TestStatus testStatus,
                          @Nullable String languageName,
                          @Nullable String recorderType,
                          @Nullable String recorderName,
                          int requestCount,
                          int queryCount,
                          int functionsCount) {
        this.name = name;
        this.appMapFile = appMapFile;
        this.languageName = languageName;
        this.recorderType = recorderType;
        this.recorderName = recorderName;
        this.requestCount = requestCount;
        this.queryCount = queryCount;
        this.functionsCount = functionsCount;
        this.testStatus = testStatus;
    }

    public @Nullable String getSystemIndependentFilepath() {
        return appMapFile != null ? appMapFile.getPath() : null;
    }

    public @Nullable String getFilename() {
        return appMapFile != null ? appMapFile.getName() : null;
    }

    public boolean hasAnyCount() {
        return requestCount > 0 || queryCount > 0 || functionsCount > 0;
    }

    public int getSortCount() {
        return requestCount * 100 + queryCount * 100 + functionsCount * 100;
    }

    public long getModificationTimestamp() {
        return appMapFile != null ? appMapFile.getTimeStamp() : Long.MAX_VALUE;
    }
}
