package appland.index;

import appland.AppMapBaseTest;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.TempDirTestFixture;
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class ClassMapTypeIndexTest extends AppMapBaseTest {
    @Override
    protected TempDirTestFixture createTempDirTestFixture() {
        return new TempDirTestFixtureImpl();
    }

    @Test
    public void emptyResult() {
        assertEmpty(ClassMapTypeIndex.findItems(getProject(), ClassMapItemType.Package));
        assertEmpty(ClassMapTypeIndex.findItems(getProject(), ClassMapItemType.Query));
        assertEmpty(ClassMapTypeIndex.findItems(getProject(), ClassMapItemType.HTTP));
    }

    @Test
    public void singleFile() throws Exception {
        // use fixture dir, because the index only analyzes files with name classMap.json
        var root = myFixture.copyDirectoryToProject("classMaps/projectSingleFile", "root");

        withContentRoot(root, () -> {
            // root items
            assertSize(1, ClassMapTypeIndex.findItems(getProject(), ClassMapItemType.Database));
            assertSize(1, ClassMapTypeIndex.findItems(getProject(), ClassMapItemType.HTTP));

            // leafs
            assertSize(22 + 2, ClassMapTypeIndex.findItems(getProject(), ClassMapItemType.Package));
            assertSize(80, ClassMapTypeIndex.findItems(getProject(), ClassMapItemType.Class));
            assertSize(23, ClassMapTypeIndex.findItems(getProject(), ClassMapItemType.Query));
            assertSize(1, ClassMapTypeIndex.findItems(getProject(), ClassMapItemType.Route));

            // each of the leaf item results must have two files attached, because the project contains a duplicate classMap
            assertItemsFileCount("One associated AppMap file expected",
                    1,
                    List.of(ClassMapItemType.Package, ClassMapItemType.Class, ClassMapItemType.Query, ClassMapItemType.Route));
        });
    }

    @Test
    public void unexpectedClassMapType() throws Exception {
        // use fixture dir, because the index only analyzes files with name classMap.json
        var root = myFixture.copyDirectoryToProject("classMaps/unexpectedClassMapType", "root");

        withContentRoot(root, () -> {
            // items must still be found even if one of the items has an unknown class map type
            assertSize(1, ClassMapTypeIndex.findItems(getProject(), ClassMapItemType.Database));
            assertSize(1, ClassMapTypeIndex.findItems(getProject(), ClassMapItemType.HTTP));
        });
    }

    @Test
    public void duplicateItems() throws Exception {
        // use fixture dir, because the index only analyzes files with name classMap.json
        var root = myFixture.copyDirectoryToProject("classMaps/projectDuplicateIds", "root");

        withContentRoot(root, () -> {
            // root items
            assertSize(1, ClassMapTypeIndex.findItems(getProject(), ClassMapItemType.Database));
            assertSize(1, ClassMapTypeIndex.findItems(getProject(), ClassMapItemType.HTTP));

            // each of the leaf item results must have two files attached, because the project contains a duplicate classMap
            assertItemsFileCount("Two associated AppMap files expected",
                    2,
                    List.of(ClassMapItemType.Package, ClassMapItemType.Class, ClassMapItemType.Query, ClassMapItemType.Route));
        });
    }

    @Test
    public void insideExcluded() throws Exception {
        var root = myFixture.copyDirectoryToProject("classMaps/projectDuplicateIds", "root");

        withContentRoot(root, () -> {
            withExcludedFolder(root, () -> {
                // root items, classMap files in excluded folders must be processed by the query
                assertSize(1, ClassMapTypeIndex.findItems(getProject(), ClassMapItemType.Database));
                assertSize(1, ClassMapTypeIndex.findItems(getProject(), ClassMapItemType.HTTP));
            });
        });
    }

    private void assertItemsFileCount(String message, int expected, List<ClassMapItemType> types) {
        for (var type : types) {
            var result = ClassMapTypeIndex.findItems(getProject(), type);
            assertFalse(result.isEmpty());

            for (var value : result.values()) {
                assertEquals(message, expected, value.size());
            }
        }
    }

    private void assertEmpty(@NotNull Map<ClassMapItem, List<VirtualFile>> data) {
        assertSize(0, data);
    }

    private void assertSize(int expectedSize, @NotNull Map<ClassMapItem, List<VirtualFile>> data) {
        assertEquals(expectedSize, data.size());
    }
}