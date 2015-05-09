/**
 * Created by philip schwarz in 2010-2011.
 */

def dirContainingOldRelease = args[0]
def dirContainingNewRelease = args[1]
def dirContainingPatch = args[2]
def beyondCompareReportFile = args[3]
def patchCreationLogDir = args[4]
def releaseComponent = args[5]

new PatchGenerator
(   dirContainingOldRelease,
    dirContainingNewRelease,
    dirContainingPatch,
    patchCreationLogDir,
    releaseComponent).processReleases(beyondCompareReportFile)

enum OrphanType {LEFT_ORPHAN, RIGHT_ORPHAN}

public class PatchGenerator
{
    // The following 6 are initialised in the the constructor
    private static String DIR_CONTAINING_OLD_RELEASE
    private static String DIR_CONTAINING_NEW_RELEASE
    private static String DIR_CONTAINING_PATCH
    private static String PATCH_CREATION_LOG_DIR
    private static String RELEASE_COMPONENT	// one of 'core', 'field-dev' and 'source'

    private static final AntBuilder ant = new AntBuilder();
    private static final String APPNET_LIB_PATH = "appnet\\lib"
    private static final String APPNET_LIB_JAR_PATH_ROOT = APPNET_LIB_PATH + "\\exploded-"
    private static final String EXPLODED_EAR_PATH = "appnet\\bin\\config\\JBoss50\\JRiskJBossServer\\deploy\\jrisk.ear"
    private static final String EXPLODED_EAR_JAR_PATH_ROOT = EXPLODED_EAR_PATH + "\\exploded-"
    private static final String APPNET_SRC_PATH = "appnet\\src"
    private static final String APPNET_SRC_JAR_PATH_ROOT = APPNET_SRC_PATH + "\\exploded-"

    private static final String RELEASE_COMPARISON_LOG_FILE = "release-comparison.log"
    private static final String RELEASE_COMPARISON_SUMMARY_LOG_FILE = "release-comparison-summary.log"
    private static final String PATCH_CONTENTS_LOG_FILE = "patch-contents.log"
    private static final String LOG_FILE_WITH_INFO_EXTRACTED_FROM_BEYOND_COMPARE = "info-extracted-from-beyond-compare-report.log"
    private static final String LOG_FILE_WITH_FILES_COPIED_FROM_NEW_RELEASE_TO_PATCH = "files-copied-from-new-release-to-patch.log"

    private static final String REPORT_SEPARATOR = '-' * 80
    private static final String START_OF_ERROR_MESSAGE_LINE = '>' * 7

    private static final String BC_REPORT_SECTION_NAME_FOR_LEFT_ORPHAN_FILES = 'Left Orphan Files'
    private static final String BC_REPORT_SECTION_NAME_FOR_RIGHT_ORPHAN_FILES = 'Right Orphan Files'
    private static final String BC_REPORT_SECTION_NAME_FOR_LEFT_NEWER_FILES = 'Left Newer Files'
    private static final String BC_REPORT_SECTION_NAME_FOR_RIGHT_NEWER_FILES = 'Right Newer Files'
    private static final String BC_REPORT_SECTION_NAME_FOR_DIFFERENCES_FILES = 'Differences Files'
    private static final String BC_REPORT_SEPARATOR = '-' * 250

    // Section names, in the order they are expected in the report
    private static final List<String> BC_EXPECTED_REPORT_SECTION_NAMES =
        [BC_REPORT_SECTION_NAME_FOR_LEFT_ORPHAN_FILES,
         BC_REPORT_SECTION_NAME_FOR_RIGHT_ORPHAN_FILES,
         BC_REPORT_SECTION_NAME_FOR_LEFT_NEWER_FILES,
         BC_REPORT_SECTION_NAME_FOR_RIGHT_NEWER_FILES,
         BC_REPORT_SECTION_NAME_FOR_DIFFERENCES_FILES]

    private List changedAppnetLibEjbJarNames = []
    private List changedExplodedEarEjbJarNames = []
    private List appnetLibJarChangesPaths = []    // Excluding files in new JARs
    private List appnetSourceJarChangesPaths = [] // Excluding files in new JARs
    private List appnetLibJarNewFilePaths = []    // Excluding files in new JARs
    private List appnetSourceJarNewFilePaths = [] // Excluding files in new JARs
    private List fileChangePaths = []             // Changed files that are NOT in JARs
    private List newFilePaths = []                // New files that are NOT in JARs
    private List patchContents = []               // The path, in the patch, of each file copied to the patch
    private List allCopyOperations = []           // For each file copied from new release to patch: "copied <source file> \n to <destination file>"

    // The next two are maintained but not currently used
    private List appnetLibEjbJarPaths = []   // Paths of changed files in EJB JARs (existing JARs, not new ones) in appnet lib
    private List explodedEarEjbJarPaths = [] // "           "             "            "              "          in the exploded EAR

    public PatchGenerator(String dirContainingOldRelease, String dirContainingNewRelease, String dirContainingPatch, String patchCreationLogDir, String releaseComponent)
    {
        this.DIR_CONTAINING_OLD_RELEASE = dirContainingOldRelease
        this.DIR_CONTAINING_NEW_RELEASE = dirContainingNewRelease
        this.DIR_CONTAINING_PATCH = dirContainingPatch
        this.PATCH_CREATION_LOG_DIR = patchCreationLogDir
        this.RELEASE_COMPONENT = releaseComponent
    }

    public void processReleases(String beyondCompareReportFile)
    {
        checkReportExists(beyondCompareReportFile)
        // In the Beyond Compare Diff Report there are three types of line: left orphan files
        // (i.e. deleted files), right orphan files (i.e. new files), and changed files.
        List lines = new File(beyondCompareReportFile).readLines()

        checkReportHasExpectedFormat(lines)

        // The only reason we are interested in left orphan files is that we have to warn the user
        // of their existence. The only place we'll use these lines is when we create report
        // files describing the outcome of processing the releases.
        List linesWithLeftOrphanFiles = getLinesWithLeftOrphanFiles(lines)

        // Get hold of right orphan files, i.e. files that are in the new release but not in the old one,
        // because they are a necessary (though not sufficient) condition for the presence of new JARs:
        // if there is a new JAR, then each of its files shows up in the diff report as a right orphan file.
        // The reason we are interested in new JARs is that they are a special case: rather than
        // copying their contents from the new release to the patch, we want to copy the whole JARs across.
        List linesWithRightOrphanFiles = getLinesWithRightOrphanFiles(lines)

        if(RELEASE_COMPONENT.equals("source"))
        {
            processSourceComponentOfReleases(lines, beyondCompareReportFile, linesWithLeftOrphanFiles, linesWithRightOrphanFiles)
        }
        else // RELEASE_COMPONENT is 'core' or 'field-dev'
        {
            processCoreOrFieldDevComponentOfReleases(lines, beyondCompareReportFile, linesWithLeftOrphanFiles, linesWithRightOrphanFiles)
        }
    }

    public void processCoreOrFieldDevComponentOfReleases(
            List lines, String beyondCompareReportFile, List linesWithLeftOrphanFiles, List linesWithRightOrphanFiles
    )
    {
        // Work out what the new JARs are (if any) in appnet\lib and the exploded EAR,
        // and copy them across from the new release to the patch.
        List linesRelatingToNewAppnetLibJars = getLinesRelatingToNewAppnetLibJars(linesWithRightOrphanFiles)
        List linesRelatingToNewExplodedEarJars = getLinesRelatingToNewExplodedEarJars(linesWithRightOrphanFiles)
        List linesRelatingToNewAppnetSourceJars = getLinesRelatingToNewAppnetSourceJars(linesWithRightOrphanFiles)
        List newAppnetLibJarNames = linesRelatingToNewAppnetLibJars.collect{ getAppnetLibJarNameFrom(it) }.unique()
        List newExplodedEarJarNames = linesRelatingToNewExplodedEarJars.collect{ getExplodedEarJarNameFrom(it) }.unique()
        copyJARsFromNewReleaseToPatch(newAppnetLibJarNames, newExplodedEarJarNames)

        // Having processed the right orphan lines due to new JARs, let's now get hold of all remaining lines,
        // i.e. changed lines, and right orphan lines which are NOT due to new JARs
        List linesWithChangedFiles = getLinesWithChangedFiles(lines)
        List remainingLines =
            linesWithRightOrphanFiles
            - linesRelatingToNewAppnetLibJars
            - linesRelatingToNewExplodedEarJars
            - linesRelatingToNewAppnetSourceJars
            + linesWithChangedFiles

        // Extract from each line a new or changed file:
        // (a) If the file belongs to an EJB JAR, then rather than copying its contents to the patch, copy the whole JAR across
        // (b) If the file belongs to a non-EJB JAR (e.g. a class file, config file, test result file), then copy it to the
        //     patch, so that it has the same location in the patch as it has in the existing JAR
        // (c) Otherwise copy the file to the patch so that is has the same location in the patch as it has in the old release.
        // NOTE: Because we dealt with new JARs earlier, we know that each JAR we handle here is an existing JAR.
        remainingLines.each { processCoreOrFieldDevLine(it) }

        logResults(linesWithLeftOrphanFiles, linesWithRightOrphanFiles, linesWithChangedFiles, newAppnetLibJarNames,
                newExplodedEarJarNames)
    }

    public void processSourceComponentOfReleases(List lines, String beyondCompareReportFile, List linesWithLeftOrphanFiles, List linesWithRightOrphanFiles)
    {
        // Work out what the new JARs are (if any) in appnet\src, and copy them across from the new release to the patch.
        List linesRelatingToNewAppnetSourceJars = getLinesRelatingToNewAppnetSourceJars(linesWithRightOrphanFiles)
        List newAppnetSourceJarNames = linesRelatingToNewAppnetSourceJars.collect{ getAppnetSourceJarNameFrom(it) }.unique()
        copyAppnetSourceJARsFromNewReleaseToPatch(newAppnetSourceJarNames)

        // Having processed the right orphan lines due to new JARs, let's now get hold of all remaining lines,
        // i.e. changed lines, and right orphan lines which are NOT due to new JARs
        List linesWithChangedFiles = getLinesWithChangedFiles(lines)
        List remainingLines = linesWithRightOrphanFiles - linesRelatingToNewAppnetSourceJars + linesWithChangedFiles

        // Extract from each line a new or changed file belonging to a source JAR.
        // NOTE: Because we dealt with new JARs earlier, we know that each JAR we handle here is an existing JAR.
        remainingLines.each { processSourceLine(it) }

        logResults(linesWithLeftOrphanFiles, linesWithRightOrphanFiles, linesWithChangedFiles, newAppnetSourceJarNames)
    }

    def getLinesRelatingToNewAppnetLibJars(List linesWithRightOrphanFiles)
    {
        def linesRelatingToAppnetLibJars = linesWithRightOrphanFiles.findAll{ it.startsWith( APPNET_LIB_JAR_PATH_ROOT ) }
        def linesRelatingToNewAppnetLibJars = linesRelatingToAppnetLibJars.findAll{ ! existsInAppletLibDirOfOldRelease(getAppnetLibJarNameFrom(it)) }
        return linesRelatingToNewAppnetLibJars
    }

    def getLinesRelatingToNewExplodedEarJars(List linesWithRightOrphanFiles)
    {
        def linesRelatingToExplodedEarJars = linesWithRightOrphanFiles.findAll{ it.startsWith( EXPLODED_EAR_JAR_PATH_ROOT ) }
        def linesRelatingToNewExplodedEarJars = linesRelatingToExplodedEarJars.findAll{ ! existsInExplodedEarDirOfOldRelease(getExplodedEarJarNameFrom(it)) }
        return linesRelatingToNewExplodedEarJars
    }

    def getLinesRelatingToNewAppnetSourceJars(List linesWithRightOrphanFiles)
    {
        def linesRelatingToSourceJars = linesWithRightOrphanFiles.findAll{ it.startsWith( APPNET_SRC_JAR_PATH_ROOT ) }
        def linesRelatingToNewSourceJars = linesRelatingToSourceJars.findAll{ ! existsInAppnetSourceDirOfOldRelease(getAppnetSourceJarNameFrom(it)) }
        return linesRelatingToNewSourceJars
    }

    int sumTheSizeOfEachListIn(List list)
    {
        return list.collect { it.size() }.sum()
    }

    void processCoreOrFieldDevLine(String line)
    {
        if (containsJARinJBossExplodedEAR(line) ) // these are all EJB JARs, except for special case core-integration-0.0.1-SNAPSHOT.jar, which we don't yet deal with
        {
            handleChangedJARinJBossExplodedEAR(line)
        }
        else if (containsJARinAppnetLib(line) )
        {
            handleChangedJARinAppnetLib(line)
        }
        else  // Any other changes or additions
        {
            handleNewOrChangedFile(line)
        }
    }

    void processSourceLine(String line)
    {
        // NOTE: The source component of a patch is only supposed to contain source JARs
        // So ignore all other lines
        if (containsJARinAppnetSrc(line))
        {
            handleChangedJARinAppnetSource(line)
        }
    }

    boolean existsInOldRelease(String file)
    {
        new File(DIR_CONTAINING_OLD_RELEASE + "\\" + file).exists()
    }

    boolean existsInAppnetSourceDirOfOldRelease(String jarName) { existsInDirOfOldRelease(jarName, APPNET_SRC_JAR_PATH_ROOT) }
    boolean existsInAppletLibDirOfOldRelease(String jarName) { existsInDirOfOldRelease(jarName, APPNET_LIB_JAR_PATH_ROOT) }
    boolean existsInExplodedEarDirOfOldRelease(String jarName) { existsInDirOfOldRelease(jarName, EXPLODED_EAR_JAR_PATH_ROOT) }

    boolean existsInDirOfOldRelease(String jarName, String jarPathRoot)
    {
        new File(DIR_CONTAINING_OLD_RELEASE + "\\" + jarPathRoot + jarName ).exists()
    }

    List getLinesWithLeftOrphanFiles(List lines) { getLinesWithOrphanFiles(OrphanType.LEFT_ORPHAN, lines) }
    List getLinesWithRightOrphanFiles(List lines) { getLinesWithOrphanFiles(OrphanType.RIGHT_ORPHAN, lines) }

    List getLinesWithOrphanFiles(OrphanType orphanType, List lines)
    {
        int indexOfOrphanSectionName
        int indexOfSubsequentSectionName
        if (orphanType == OrphanType.LEFT_ORPHAN)
        {
            indexOfOrphanSectionName = lines.indexOf(BC_REPORT_SECTION_NAME_FOR_LEFT_ORPHAN_FILES)
            indexOfSubsequentSectionName = lines.indexOf(BC_REPORT_SECTION_NAME_FOR_RIGHT_ORPHAN_FILES)
        }
        else // orphanType == RIGHT_ORPHAN
        {
            indexOfOrphanSectionName = lines.indexOf(BC_REPORT_SECTION_NAME_FOR_RIGHT_ORPHAN_FILES)
            indexOfSubsequentSectionName = lines.indexOf(BC_REPORT_SECTION_NAME_FOR_LEFT_NEWER_FILES)
        }
        getOrphansFromReportSection(lines, indexOfOrphanSectionName, indexOfSubsequentSectionName)
    }

    List<String> getOrphansFromReportSection(List<String> lines, int indexOfOrphanSectionName, int indexOfSubsequentSectionName)
    {
        List<String> orphanLines
        if (orphanSectionIsEmpty(indexOfOrphanSectionName, indexOfSubsequentSectionName))
        {
            orphanLines = []
        }
        else
        {
            // WE ARE LOOKING AT THE FOLLOWING:
            // orphan section name
            // -----------------------
            // first orphan
            // ...intervening orphans...
            // last orphan
            // -----------------------
            // blank line
            // subsequent section name
            int indexOfFirstOrphan = indexOfOrphanSectionName + 2 // skip separator
            int indexOfLastOrphan = indexOfSubsequentSectionName - 3 // skip blank line and separator
            orphanLines = lines[indexOfFirstOrphan..indexOfLastOrphan]
        }
        orphanLines
    }

    boolean orphanSectionIsEmpty(int indexOfOrphanSectionName, int indexOfSubsequentSectionName)
    {
        // TRUE IF WE HAVE THE FOLLOWING:
        // orphan section name
        // -----------------------
        // blank line
        // subsequent section name
        indexOfOrphanSectionName == (indexOfSubsequentSectionName - 3 )
    }

    List getLinesWithChangedFiles(List lines)
    {
        List<String> result
        if (differencesFilesSectionIsEmpty(lines))
        {
            result = []
        }
        else
        {
            // WE ARE LOOKING AT THE FOLLOWING (NOTE - NO BLANK LINE AT THE END):
            // differences files section name
            // -----------------------
            // first orphan
            // ...intervening orphans...
            // last orphan
            // -----------------------
            def indexOfFirstDifference = lines.indexOf(BC_REPORT_SECTION_NAME_FOR_DIFFERENCES_FILES) + 2 // i.e. skip the line of '-' chars after the section title
            def indexOfLastDifference  = lines.size() - 2 // i.e. skip blank line and separator line
            result = lines[indexOfFirstDifference..indexOfLastDifference]
        }
        result
    }

    boolean differencesFilesSectionIsEmpty(List<String> lines)
    {
        // TRUE IF THE FILE ENDS AS FOLLOWS (NOTE - NO BLANK LINE AT THE END):
        // differences files section name
        // --------------------------------
        // blank line
        (lines[-2] == BC_REPORT_SECTION_NAME_FOR_DIFFERENCES_FILES) && (lines[-1] == BC_REPORT_SEPARATOR)
    }

    void handleChangedJARinJBossExplodedEAR(String diffReportLine)
    {
        String jarName = getExplodedEarJarNameFrom(diffReportLine) // e.g. get 'foo' from '...\exploded-foo.jar\...'
        String jarPath = getPathOfJARinExplodedEAR(jarName) // e.g. get 'appnet\bin\foo.jar' from 'foo'
        if (!changedExplodedEarEjbJarNames.contains(jarName)) // Only do this the first time we see a changed file from this JAR
        {
            copyFromNewReleaseToPatch(jarPath)	// ADD THE WHOLE JAR TO THE PATCH
            explodedEarEjbJarPaths.add(jarPath)
            changedExplodedEarEjbJarNames.add(jarName)
        }
    }

    void handleChangedJARinAppnetLib(String diffReportLine)
    {
        String jarName = getAppnetLibJarNameFrom(diffReportLine) // e.g. get 'foo' from '...\exploded-foo.jar\...'
        // ############### EJB JARs in appnet lib
        if (containsEJBJAR(jarName)) // the jar name contains 'ejb'
        {
            String jarPath = getPathOfJARinAppnetLib(jarName) // e.g. get '...exploded-ear-path...\foo.jar' from 'foo'
            if (!changedAppnetLibEjbJarNames.contains(jarName))
            {
                copyFromNewReleaseToPatch(jarPath) // ADD THE WHOLE JAR TO THE PATCH
                appnetLibEjbJarPaths.add(jarPath)
                changedAppnetLibEjbJarNames.add(jarName)
            }
        }
        else // ############### normal JARs in appnet lib
        {
            String jarContentPath = getPathOfJarContent(diffReportLine) // e.g. get 'bar\someFile.txt' from '...\exploded-foo.jar\bar\somFile.txt'
            copyJarFileFromNewReleaseToPatch(diffReportLine, jarContentPath) // ADD THE FILE TO THE PATCH
            if ( existsInOldRelease(diffReportLine) )
            {
                appnetLibJarChangesPaths.add(diffReportLine)
            }
            else
            {
                appnetLibJarNewFilePaths.add(diffReportLine)
            }
        }
    }

    void handleChangedJARinAppnetSource(String diffReportLine)
    {
        String jarContentPath = getPathOfJarContent(diffReportLine) // e.g. get 'bar\someFile.txt' from '...\exploded-foo.jar\bar\somFile.txt'
        copyJarFileFromNewReleaseToPatch(diffReportLine, jarContentPath) // ADD THE FILE TO THE PATCH
        if ( existsInOldRelease(diffReportLine) )
        {
            appnetSourceJarChangesPaths.add(diffReportLine)
        }
        else
        {
            appnetSourceJarNewFilePaths.add(diffReportLine)
        }
    }

    void handleNewOrChangedFile(String diffReportLine)
    {
        copyFromNewReleaseToPatch(diffReportLine) // e.g. copy appnet\bin\someFile.txt
        if ( existsInOldRelease(diffReportLine) )
        {
            fileChangePaths.add(diffReportLine)
        }
        else
        {
            newFilePaths.add(diffReportLine)
        }
    }

    String getAppnetSourceJarNameFrom(String filePath) { getJarNameFrom(filePath, APPNET_SRC_JAR_PATH_ROOT) }
    String getExplodedEarJarNameFrom(String filePath) { getJarNameFrom(filePath, EXPLODED_EAR_JAR_PATH_ROOT) }
    String getAppnetLibJarNameFrom(String filePath) { getJarNameFrom(filePath, APPNET_LIB_JAR_PATH_ROOT) }

    String getJarNameFrom(String filePath, String jarPathRoot)
    {
        def jarNameStartIndex = jarPathRoot.size()
        def jarNameEndIndex = filePath.indexOf(".jar") + 4 // include .jar in the name
        def jarName = filePath.substring(jarNameStartIndex,jarNameEndIndex)
    }

    String getPathOfJARinExplodedEAR(String jarName) { return EXPLODED_EAR_PATH+ "\\" + jarName }
    String getPathOfJARinAppnetLib(String jarName) { return APPNET_LIB_PATH + "\\" + jarName }

    String getPathOfJarContent(String filePath)
    {
        int jarNameEndIndex = filePath.indexOf(".jar")
        int jarContentStartIndex = jarNameEndIndex + 5 // add 5 to skip 'jar\\'
        String jarContentPath = filePath.substring(jarContentStartIndex) // e.g. get 'bar\someFile.txt' from '...\exploded-foo.jar\bar\somFile.txt'
    }

    void copyAppnetSourceJARsFromNewReleaseToPatch(List newAppnetSourceJars)
    {
        newAppnetSourceJars.each{ copyFromNewReleaseToPatch( APPNET_SRC_PATH + "\\" + it ) }
    }
    void copyJARsFromNewReleaseToPatch(List newAppnetLibJars, List newExplodedEarJars)
    {
        newAppnetLibJars.each{ copyFromNewReleaseToPatch( APPNET_LIB_PATH + "\\" + it ) }
        newExplodedEarJars.each{ copyFromNewReleaseToPatch( EXPLODED_EAR_PATH + "\\" + it ) }
    }
    void copyFromNewReleaseToPatch(String file)
    {
        copy( DIR_CONTAINING_NEW_RELEASE + "\\" + file, DIR_CONTAINING_PATCH + "\\" + file)
        allCopyOperations.add("copied " + file + System.getProperty("line.separator") + "    to " + file )
        patchContents.add(file)

    }

    void copyJarFileFromNewReleaseToPatch(String source, String jarContentPath)
    {
        copy( DIR_CONTAINING_NEW_RELEASE + "\\" + source, DIR_CONTAINING_PATCH + "\\" + jarContentPath)
        allCopyOperations.add("copied " + source + System.getProperty("line.separator") + "    to " + jarContentPath )
        patchContents.add(jarContentPath )
    }

    void copy(String file, toFile)
    {
        ant.copy(file:file, toFile:toFile)
    }
    boolean containsJARinJBossExplodedEAR(String filePath) { filePath.startsWith(EXPLODED_EAR_JAR_PATH_ROOT) }
    boolean containsJARinAppnetLib(String filePath) { filePath.startsWith(APPNET_LIB_JAR_PATH_ROOT) }
    boolean containsJARinAppnetSrc(String filePath) { filePath.startsWith(APPNET_SRC_JAR_PATH_ROOT) }
    boolean containsEJBJAR(String jarName) { jarName =~ /ejb/ }

    void checkReportExists(String beyondCompareReportFile)
    {
        assert new File(beyondCompareReportFile).exists(), createErrorMessage("The following Beyond Compare report file doesn\'t exist: \'" + beyondCompareReportFile + "\'.")
    }

    void checkReportHasExpectedFormat(List<String> lines)
    {
        println "EXPECTED = " + BC_EXPECTED_REPORT_SECTION_NAMES
        println "FOUND    = " + lines.intersect(BC_EXPECTED_REPORT_SECTION_NAMES)
        assert lines.intersect(BC_EXPECTED_REPORT_SECTION_NAMES) == BC_EXPECTED_REPORT_SECTION_NAMES, "The Beyond Compare report does not contain some or all of the following expected sections: " + BC_EXPECTED_REPORT_SECTION_NAMES
        // Get the indexes of the expected section names in the report
        def indexesOfSections = BC_EXPECTED_REPORT_SECTION_NAMES.collect{ lines.indexOf(it) }
        println "indexesOfSections    = " + indexesOfSections
        println "sorted indexesOfSections    = " + indexesOfSections.sort()
        // True if the indexes are already sorted, i.e. section N is before section N+1
        assert indexesOfSections == indexesOfSections.sort(), "The Beyond Compare report does not contain the following sections in the given order: " + BC_EXPECTED_REPORT_SECTION_NAMES
    }

    String createErrorMessage(String text)
    {
        "\n" +
                START_OF_ERROR_MESSAGE_LINE + "\n" +
                START_OF_ERROR_MESSAGE_LINE + "ERROR: " + text + "\n" +
                START_OF_ERROR_MESSAGE_LINE + "\n"
    }

    void writeReportSection(String sectionName, entries, Writer writer)
    {
        if (entries.size() == 0)
        {
            writer.writeLine(sectionName + ": NONE")
        }
        else
        {
            writer.writeLine(entries.size() + " " + sectionName + ":")
            writer.writeLine(REPORT_SEPARATOR)
            entries.each{ writer.writeLine(it) }
            writer.writeLine(REPORT_SEPARATOR)
        }
        writer.writeLine("")
    }

    void logResults(
            List<String> linesWithLeftOrphanFiles,
            List<String> linesWithOrphanFiles,
            List<String> linesWithChangedFiles,
            List<String> newAppnetLibJarNames,
            List<String> newExplodedEarJarNames
    )
    {
        logInfoExtractedFromBeyondCompareReport(linesWithLeftOrphanFiles, linesWithOrphanFiles, linesWithChangedFiles)
        logAllCopyOperations(allCopyOperations)
        logPatchContents(patchContents)
        logReleaseComparisonResultsAndSummary(linesWithLeftOrphanFiles, newAppnetLibJarNames, newExplodedEarJarNames, changedAppnetLibEjbJarNames, changedExplodedEarEjbJarNames, appnetLibJarNewFilePaths, appnetLibJarChangesPaths, fileChangePaths, newFilePaths)
    }

    void logResults(
            List<String> linesWithLeftOrphanFiles,
            List<String> linesWithRightOrphanFiles,
            List<String> linesWithChangedFiles,
            List<String> newAppnetSourceJarNames
    )
    {
        logInfoExtractedFromBeyondCompareReport(linesWithLeftOrphanFiles, linesWithRightOrphanFiles, linesWithChangedFiles)
        logAllCopyOperations(allCopyOperations)
        logPatchContents(patchContents)
        logReleaseComparisonResultsAndSummary(linesWithLeftOrphanFiles, newAppnetSourceJarNames, appnetSourceJarNewFilePaths, appnetSourceJarChangesPaths)
    }

    void logReleaseComparisonResultsAndSummary(List<String> linesWithLeftOrphanFiles, List<String> newAppnetSourceJarNames, List<String> appnetSourceJarNewFilePaths, List<String> appnetSourceJarChangesPaths)
    {
        int totalNumberOfChanges = sumTheSizeOfEachListIn( [newAppnetSourceJarNames, appnetSourceJarNewFilePaths, appnetSourceJarChangesPaths] )

        def releaseComparisonSummaryLogFile = createLogFile(RELEASE_COMPARISON_SUMMARY_LOG_FILE)
        releaseComparisonSummaryLogFile.withWriter
                {
                    writer ->
                        writer.writeLine("")
                        writer.writeLine("#########################################################")
                        writer.writeLine("# RELEASE COMPARISON RESULTS SUMMARY - SOURCE COMPONENT #")
                        writer.writeLine("#########################################################")
                        writer.writeLine("")
                        warnIfThereAreLeftOrphanFiles(writer, linesWithLeftOrphanFiles)
                        writer.writeLine("")
                        writer.writeLine("New appnet\\src JARs: " + sizeOrNone(newAppnetSourceJarNames))
                        writer.writeLine("")
                        writer.writeLine("New files in appnet\\src JARs: " + sizeOrNone(appnetSourceJarNewFilePaths))
                        writer.writeLine("")
                        writer.writeLine("Changed files in appnet\\src JARs: " + sizeOrNone(appnetSourceJarChangesPaths))
                        writer.writeLine("")
                        writer.writeLine("TOTAL NUMBER OF CHANGES: " + totalNumberOfChanges)
                }

        def releaseComparisonLogFile = createLogFile( RELEASE_COMPARISON_LOG_FILE )
        releaseComparisonLogFile.withWriter
                {
                    writer ->
                        writer.writeLine("")
                        writer.writeLine("#################################################")
                        writer.writeLine("# RELEASE COMPARISON RESULTS - SOURCE COMPONENT #")
                        writer.writeLine("#################################################")
                        writer.writeLine("")
                        warnIfThereAreLeftOrphanFiles(writer, linesWithLeftOrphanFiles)
                        writer.writeLine("")
                        writeReportSection("New appnet\\src JARs",newAppnetSourceJarNames,writer)
                        writeReportSection("New files in appnet\\src JARs",appnetSourceJarNewFilePaths,writer)
                        writeReportSection("Changed files in appnet\\src JARs",appnetSourceJarChangesPaths,writer)
                }
    }

    void logReleaseComparisonResultsAndSummary(
            List<String> linesWithLeftOrphanFiles,
            List<String> newAppnetLibJarNames,
            List<String> newExplodedEarJarNames,
            List<String> changedAppnetLibEjbJarNames,
            List<String> changedExplodedEarEjbJarNames,
            List<String> appnetLibJarNewFilePaths,
            List<String> appnetLibJarChangesPaths,
            List<String> fileChangePaths,
            List<String> newFilePaths
    )
    {
        int totalNumberOfChanges = sumTheSizeOfEachListIn(
                [changedExplodedEarEjbJarNames,
                 changedAppnetLibEjbJarNames,
                 appnetLibJarChangesPaths,
                 appnetLibJarNewFilePaths,
                 fileChangePaths,
                 newFilePaths,
                 newAppnetLibJarNames,
                 newExplodedEarJarNames ]
        )

        def releaseComparisonSummaryLogFile = createLogFile(RELEASE_COMPARISON_SUMMARY_LOG_FILE)
        releaseComparisonSummaryLogFile.withWriter
                {
                    writer ->
                        writer.writeLine("")
                        writeReportBanner(writer, "# RELEASE COMPARISON RESULTS SUMMARY - " + RELEASE_COMPONENT.toUpperCase() + " COMPONENT #")
                        writer.writeLine("")
                        warnIfThereAreLeftOrphanFiles(writer, linesWithLeftOrphanFiles)
                        writer.writeLine("")
                        writer.writeLine("New appnet\\lib JARs: " + sizeOrNone(newAppnetLibJarNames))
                        writer.writeLine("")
                        writer.writeLine("New exploded EAR JARs: " + sizeOrNone(newExplodedEarJarNames))
                        writer.writeLine("")
                        writer.writeLine("Changed appnet\\lib EJB JARs: " + sizeOrNone(changedAppnetLibEjbJarNames))
                        writer.writeLine("")
                        writer.writeLine("Changed exploded EAR EJB JARs: " + sizeOrNone(changedExplodedEarEjbJarNames))
                        writer.writeLine("")
                        writer.writeLine("New files in appnet\\lib JARs: " + sizeOrNone(appnetLibJarNewFilePaths))
                        writer.writeLine("")
                        writer.writeLine("Changed files in appnet\\lib JARs: " + sizeOrNone(appnetLibJarChangesPaths))
                        writer.writeLine("")
                        writer.writeLine("New files outside JARs: " + sizeOrNone(newFilePaths))
                        writer.writeLine("")
                        writer.writeLine("Changed files outside JARs: " + sizeOrNone(fileChangePaths))
                        writer.writeLine("")
                        writer.writeLine("TOTAL NUMBER OF CHANGES: " + totalNumberOfChanges)
                }

        def releaseComparisonLogFile = createLogFile( RELEASE_COMPARISON_LOG_FILE )
        releaseComparisonLogFile.withWriter
                {
                    writer ->
                        writer.writeLine("")
                        writeReportBanner(writer, "# RELEASE COMPARISON RESULTS - " + RELEASE_COMPONENT.toUpperCase() + " COMPONENT #")
                        writer.writeLine("")
                        warnIfThereAreLeftOrphanFiles(writer, linesWithLeftOrphanFiles)
                        writer.writeLine("")
                        writeReportSection("New appnet\\lib JARs",newAppnetLibJarNames,writer)
                        writeReportSection("New exploded EAR JARs",newExplodedEarJarNames,writer)
                        writeReportSection("Changed appnet\\lib EJB JARs",changedAppnetLibEjbJarNames,writer)
                        writeReportSection("Changed exploded EAR EJB JARs",changedExplodedEarEjbJarNames,writer)
                        writeReportSection("New files in appnet\\lib JARs",appnetLibJarNewFilePaths,writer)
                        writeReportSection("Changed files in appnet\\lib JARs",appnetLibJarChangesPaths,writer)
                        writeReportSection("New files outside JARs",newFilePaths,writer)
                        writeReportSection("Changed files outside JARs",fileChangePaths,writer)
                }
    }

    String sizeOrNone(List list) { list.size() > 0 ? list.size()+"" : "NONE"}

    void logInfoExtractedFromBeyondCompareReport(List<String> linesWithLeftOrphanFiles, List<String> linesWithRightOrphanFiles, List<String> linesWithChangedFiles)
    {
        def beyondCompareInfoLogFile = createLogFile(LOG_FILE_WITH_INFO_EXTRACTED_FROM_BEYOND_COMPARE)
        beyondCompareInfoLogFile.withWriter
                {
                    writer ->
                        writer.writeLine("")
                        writeReportBanner(writer, "# INFORMATION EXTRACTED FROM BEYOND COMPARE DIFF REPORT - " + RELEASE_COMPONENT.toUpperCase() + " COMPONENT #")
                        writer.writeLine("")
                        writer.writeLine("Summary:")
                        writer.writeLine("")
                        writer.writeLine(linesWithLeftOrphanFiles.size() + " lines with Left Orphan Files" )
                        writer.writeLine(linesWithRightOrphanFiles.size() + " lines with Right Orphan Files")
                        writer.writeLine(linesWithChangedFiles.size() + " lines with Changed Files")
                        writer.writeLine("")
                        writer.writeLine("Detail:")
                        writer.writeLine("")
                        writeReportSection("Lines with Left Orphan Files", linesWithLeftOrphanFiles, writer)
                        writeReportSection("Lines with Right Orphan Files", linesWithRightOrphanFiles, writer)
                        writeReportSection("Lines with Changed Files", linesWithChangedFiles, writer)
                }
    }

    void logPatchContents(List<String> patchContents)
    {
        def patchContentsLogFile = createLogFile(PATCH_CONTENTS_LOG_FILE)
        patchContentsLogFile.withWriter
                {
                    writer ->
                        writer.writeLine("")
                        writeReportBanner(writer, "# PATCH CONTENTS - " + RELEASE_COMPONENT.toUpperCase() + " COMPONENT #")
                        writer.writeLine("")
                        patchContents.sort().each { writer.writeLine(it) }
                }
    }

    void logAllCopyOperations(List<String> allCopyOperations)
    {
        def copiedFilesLogFile = createLogFile(LOG_FILE_WITH_FILES_COPIED_FROM_NEW_RELEASE_TO_PATCH)
        copiedFilesLogFile.withWriter
                {
                    writer ->
                        writer.writeLine("")
                        writer.writeLine(allCopyOperations.size() + " FILES COPIED FROM " + DIR_CONTAINING_NEW_RELEASE + " TO " + DIR_CONTAINING_PATCH + ":")
                        writer.writeLine("")
                        allCopyOperations.each{ writer.writeLine( it ); writer.writeLine( "" ) }
                }
    }

    void warnIfThereAreLeftOrphanFiles(Writer writer, List linesWithLeftOrphanFiles)
    {
        if(linesWithLeftOrphanFiles.size() > 0)
        {
            writer.writeLine("WARNING: BeyondCompare found " + linesWithLeftOrphanFiles.size() + " left orphan files: these are present in the old release but not in the new release.")
            writer.writeLine("         See " + LOG_FILE_WITH_INFO_EXTRACTED_FROM_BEYOND_COMPARE + " for details.")
        }
    }

    void writeReportBanner(Writer writer, String titleLine)
    {
        writer.writeLine('#' * titleLine.size())
        writer.writeLine(titleLine)
        writer.writeLine('#' * titleLine.size())
    }

    def createLogFile(String fileName)
    {
        new File(PATCH_CREATION_LOG_DIR + "\\" + RELEASE_COMPONENT + "-" + fileName)
    }
}

