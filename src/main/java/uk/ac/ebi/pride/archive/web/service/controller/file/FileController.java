package uk.ac.ebi.pride.archive.web.service.controller.file;

import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;
import com.wordnik.swagger.annotations.ApiParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import uk.ac.ebi.pride.archive.dataprovider.file.ProjectFileSource;
import uk.ac.ebi.pride.archive.repo.assay.service.AssaySummary;
import uk.ac.ebi.pride.archive.repo.file.service.FileSummary;
import uk.ac.ebi.pride.archive.repo.project.service.ProjectSummary;
import uk.ac.ebi.pride.archive.security.assay.AssaySecureService;
import uk.ac.ebi.pride.archive.security.file.FileSecureService;
import uk.ac.ebi.pride.archive.security.project.ProjectSecureService;
import uk.ac.ebi.pride.archive.utils.config.FilePathBuilder;
import uk.ac.ebi.pride.archive.utils.streaming.FileUtils;
import uk.ac.ebi.pride.archive.web.service.error.exception.ResourceNotFoundException;
import uk.ac.ebi.pride.archive.web.service.model.file.DefaultFileComparator;
import uk.ac.ebi.pride.archive.web.service.model.file.FileDetail;
import uk.ac.ebi.pride.archive.web.service.model.file.FileDetailList;
import uk.ac.ebi.pride.archive.web.service.util.IdMapper;
import uk.ac.ebi.pride.archive.web.service.util.ObjectMapper;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

/**
 * @author Rui Wang
 * @author florian@ebi.ac.uk
 * @since 1.0.4
 */
@Api(value = "file", description = "retrieve details about dataset files", position = 2)
@Controller
@RequestMapping(value = "/file")
public class FileController {

    private static final Logger logger = LoggerFactory.getLogger(FileController.class);

    @Autowired
    private FileSecureService fileService;

    @Autowired
    private ProjectSecureService projectService;

    @Autowired
    private AssaySecureService assayService;

    @Autowired
    private FileUtils fileUtils;

    @Autowired
    private FilePathBuilder filePathBuilder;

    @Value("#{fileConfig['file.location.prefix']}")
    private String fileLocationPrefix;

    @Value("#{fileConfig['ftp.domain']}")
    private String ftpDomain;
    private URL ftpDomainUrl;

    @Value("#{fileConfig['ftp.public.base.path']}")
    private String ftpPublicRoot;
    @Value("#{fileConfig['ftp.private.base.path']}")
    private String ftpPrivateRoot;
    @Value("#{fileConfig['aspera.download.link.prefix']}")
    private String asperaLinkPrefix;
    private String httpPrivateRoot = "http://www.ebi.ac.uk/pride/archive/files/";

    @ApiOperation(value = "list files for a project", position = 1)
    @RequestMapping(value = "/list/project/{projectAccession}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public
    @ResponseBody
    FileDetailList getFilesByProjectAccession(
            @ApiParam(value = "a project accession number (example: PXD000001)")
            @PathVariable("projectAccession") String projectAccession) throws MalformedURLException {

        ProjectSummary projectSummary = projectService.findByAccession(projectAccession);
        if (projectSummary == null) {
            throw new ResourceNotFoundException("No project found for accession: " + projectAccession);
        }

        // Note: for a correct file mapping assay and project accessions are needed, which are not available
        //       from the FileSummary object. Therefore the IdMapper singleton needs to be updated for each
        //       assay/project to be used by the ObjectMapper.
        updateProjectAccCache(projectSummary.getId(), projectSummary.getAccession());
        Collection<FileSummary> fileSummaries = fileService.findAllByProjectAccession(projectAccession);

        if (fileSummaries == null || fileSummaries.isEmpty()) {
            throw new ResourceNotFoundException("No files found for project: " + projectAccession);
        }
        updateAssayAccCache(fileSummaries);

        List<FileDetail> fileDetails = ObjectMapper.mapFileSummariesToWSFileDetails(fileSummaries);

        if (projectSummary.isPublicProject()) {
            URL url = buildPublicFtpUrlForProject(projectSummary.getAccession(), projectSummary.getPublicationDate());
            addDownloadableUrls(fileDetails, url);
        } else {
            // now there is no private path, so we don't add any FTP links
            // we can only use the http streaming method currently provided by the web
            addPrivateFileUrls(fileDetails, fileSummaries);

            // we probably need/want user specific private locations, so we retrieve data from the security context
//            Authentication a = SecurityContextHolder.getContext().getAuthentication();
//            UserDetails currentUser = (UserDetails)a.getPrincipal();
//            // add private URLs for the project files
//            ftpPath = buildPrivateFtpPathForProject(projectSummary.getAccession(), currentUser.getUsername());
//            addDownloadableUrls(fileDetails, ftpPath);
        }

        Collections.sort(fileDetails, new DefaultFileComparator());

        return new FileDetailList(fileDetails);
    }

    @ApiOperation(value = "count files for a project", position = 2)
    @RequestMapping(value = "/count/project/{projectAccession}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public
    @ResponseBody
    int countFilesByProjectAccession(
            @ApiParam(value = "a project accession number (example: PXD000001)")
            @PathVariable("projectAccession") String projectAccession) throws MalformedURLException {

        ProjectSummary projectSummary = projectService.findByAccession(projectAccession);
        if (projectSummary == null) {
            throw new ResourceNotFoundException("No project found for accession: " + projectAccession);
        }

        // Note: for a correct file mapping assay and project accessions are needed, which are not available
        //       from the FileSummary object. Therefore the IdMapper singleton needs to be updated for each
        //       assay/project to be used by the ObjectMapper.
        updateProjectAccCache(projectSummary.getId(), projectSummary.getAccession());
        // ToDo: add count method to file service
        Collection<FileSummary> fileSummaries = fileService.findAllByProjectAccession(projectAccession);

        if (fileSummaries == null || fileSummaries.isEmpty()) {
            throw new ResourceNotFoundException("No files found for project: " + projectAccession);
        }

        // count the files taking criteria like FileSource into account
        return countFiles(fileSummaries);
    }


    @ApiOperation(value = "list files for an assay", position = 3)
    @RequestMapping(value = "/list/assay/{assayAccession}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public
    @ResponseBody
    FileDetailList getFilesByAssayAccession(
            @ApiParam(value = "an assay accession number (example: 22134)")
            @PathVariable("assayAccession") String assayAccession) throws MalformedURLException {

        // find the files for the provided assay accession
        Collection<FileSummary> fileSummaries = fileService.findAllByAssayAccession(assayAccession);
        if (fileSummaries == null || fileSummaries.isEmpty()) {
            throw new ResourceNotFoundException("No files found for assay: " + assayAccession);
        }

        // Note: for a correct file mapping assay and project accessions are needed, which are not available
        //       from the FileSummary object. Therefore the IdMapper singleton needs to be updated for each
        //       assay/project to be used by the ObjectMapper.
        updateAssayAccCache(fileSummaries);

        long projectId = fileSummaries.iterator().next().getProjectId();
        ProjectSummary projectSummary = projectService.findById(projectId);
        updateProjectAccCache(projectId, projectSummary.getAccession());

        List<FileDetail> fileDetails = ObjectMapper.mapFileSummariesToWSFileDetails(fileSummaries);

        if (projectSummary.isPublicProject()) {
            URL url = buildPublicFtpUrlForProject(projectSummary.getAccession(), projectSummary.getPublicationDate());
            addDownloadableUrls(fileDetails, url);
        } else {
            // now there is no private path, so we don't add any FTP links
            // we can only use the http streaming method currently provided by the web
            addPrivateFileUrls(fileDetails, fileSummaries);
        }
//        else {
//            // ToDo: create real private FTP path links once they are available
//            // now there is not private path, so we don't add any FTP links
//            // we probably need/want user specific private locations, so we retrieve data from the security context
//            Authentication a = SecurityContextHolder.getContext().getAuthentication();
//            UserDetails currentUser = (UserDetails)a.getPrincipal();
//            // add private URLs for the project files
//            ftpPath = buildPrivateFtpPathForProject(projectSummary.getAccession(), currentUser.getUsername());
//            addDownloadableUrls(fileDetails, ftpPath);
//        }

        Collections.sort(fileDetails, new DefaultFileComparator());

        return new FileDetailList(fileDetails);
    }


    @ApiOperation(value = "count files for an assay", position = 4)
    @RequestMapping(value = "/count/assay/{assayAccession}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public
    @ResponseBody
    int countFilesByAssayAccession(
            @ApiParam(value = "an assay accession number (example: 22134)")
            @PathVariable("assayAccession") String assayAccession) throws MalformedURLException {

        Collection<FileSummary> fileSummaries = fileService.findAllByAssayAccession(assayAccession);

        if (fileSummaries == null || fileSummaries.isEmpty()) {
            throw new ResourceNotFoundException("No files found for assay: " + assayAccession);
        }

        // count the files taking criteria like FileSource into account
        return countFiles(fileSummaries);
    }

    private int countFiles(Collection<FileSummary> fileSummaries) {
        int fileCount = 0;
        for (FileSummary fileSummary : fileSummaries) {
            if (fileSummary.getFileSource().equals(ProjectFileSource.SUBMITTED)) {
                fileCount++;
            }
        }
        return fileCount;
    }

    /**
     * Annotate the FileDetail object in the provided collection with the FTP and Aspera download link for a given project.
     * This uses a pre-configured FTP domain URL/Aspera prefix as basis.
     * Note: all FileDetail objects in the provided list have to come from the same record.
     * Otherwise a correct link cannot be generated.
     *
     * @param fileDetails the Collection of FileDetail objects to annotate.
     * @param projectFtpUrl a URL defining the full FTP path to the directory where the files are located.
     * @throws java.net.MalformedURLException in case a proper URL could not be formed from the available details.
     */
    private void addDownloadableUrls(Collection<FileDetail> fileDetails, URL projectFtpUrl) throws MalformedURLException {
        for (FileDetail fileDetail : fileDetails) {
            String fileName = fileDetail.getFileName();
            // Files generated by PRIDE on top of the submission files are kept in a sub-directory
            URL fileUrl;
            if (fileDetail.getFileSource() == ProjectFileSource.GENERATED) {
               fileUrl = new URL(projectFtpUrl, "generated/" + fileName);
            } else {
                fileUrl = new URL(projectFtpUrl, fileName);
            }
            fileDetail.setDownloadLink(fileUrl);
            String fileUrlPath = fileUrl.getPath();
            String asperaPath = asperaLinkPrefix + fileUrlPath.substring(fileUrlPath.indexOf(ftpPublicRoot)+1);
            fileDetail.setAsperaDownloadLink(asperaPath);
        }
    }

    private void addPrivateFileUrls(Collection<FileDetail> fileDetails, Collection<FileSummary> fileSummaries) throws MalformedURLException {
        // for each FileDetail record add a private download URL
        // the private file download is currently only possible via the PRIDE Archive web using the internal file ID
        // this file ID has to be retrieved from the file records of the DB (FileSummary)
        for (FileDetail fileDetail : fileDetails) {
            FileSummary requestedFile = null;
            for (FileSummary file : fileSummaries) {
                if ( fileDetail.getFileName().equalsIgnoreCase(file.getFileName()) ) {
                    requestedFile = file;
                    break; // no need for further checks, we have found our file
                }
            }
            if (requestedFile == null) {
                throw new ResourceNotFoundException("Requested file not found: " + fileDetail.getFileName());
            }
            // generate and add the private download link
            URL privateUrl = new URL(httpPrivateRoot + requestedFile.getId());
            fileDetail.setDownloadLink(privateUrl);
        }
    }

    // ToDo: these FTP URL build methods should perhaps be moved to the FilePathBuilder?

    private URL getFtpDomainUrl() {
        if (ftpDomainUrl == null) {
            try {
                ftpDomainUrl = new URL(ftpDomain);
            } catch (MalformedURLException e) {
                logger.error("Error creating FTP base URL!", e);
            }
        }
        return ftpDomainUrl;
    }

    private URL buildPublicFtpUrlForProject(String projectAccession, Date publicationDate) throws MalformedURLException {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(publicationDate);
        int month = calendar.get(Calendar.MONTH) + 1;
        String datePath = calendar.get(Calendar.YEAR) + "/" + (month < 10 ? "0" : "") + month;
        // make sure the path ends with slash to denote a directory URL,
        // otherwise the bit after the last slash will be replaced when building a new URL
        String ftpProjectPath = ftpPublicRoot + datePath + "/" + projectAccession + "/";
        return new URL(getFtpDomainUrl(), ftpProjectPath);
    }

    private void updateAssayAccCache(Collection<FileSummary> fileSummaries) {
        for (FileSummary fileSummary : fileSummaries) {
            // there may be files with no assay ID (partial submissions),
            if (fileSummary.getAssayId() != null) {
                updateAssayAccCache(fileSummary.getAssayId());
            }
        }
    }
    private void updateAssayAccCache(long assayID) {
        updateAssayAccCache(assayID, null);
    }
    private void updateAssayAccCache(long assayID, String assayAccession) {
        if ( !IdMapper.getInstance().containsAssayId(assayID) ) {
            if (assayAccession == null || assayAccession.trim().isEmpty()) {
                AssaySummary assay = assayService.findById(assayID);
                assayAccession = assay.getAccession();
            }
            IdMapper.getInstance().storeAssayAccession(assayID, assayAccession);
        }
    }

    private void updateProjectAccCache(long projectID, String projectAccession) {
        if ( !IdMapper.getInstance().containsProteinId(projectID) ) {
            if (projectAccession == null || projectAccession.trim().isEmpty()) {
                ProjectSummary project = projectService.findById(projectID);
                projectAccession = project.getAccession();
            }
            IdMapper.getInstance().storeProteinAccession(projectID, projectAccession);
        }
    }


}
