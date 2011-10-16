
package com.myyearbook.hudson.plugins.confluence;

import hudson.FilePath;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.rmi.RemoteException;

import jenkins.plugins.confluence.soap.v1.ConfluenceSoapService;
import jenkins.plugins.confluence.soap.v1.RemoteAttachment;
import jenkins.plugins.confluence.soap.v1.RemotePage;
import jenkins.plugins.confluence.soap.v1.RemotePageSummary;
import jenkins.plugins.confluence.soap.v1.RemotePageUpdateOptions;
import jenkins.plugins.confluence.soap.v1.RemoteServerInfo;
import jenkins.plugins.confluence.soap.v1.RemoteSpace;

/**
 * Connection to Confluence
 *
 * @author Joe Hansche <jhansche@myyearbook.com>
 */
public class ConfluenceSession {
    /**
     * Confluence SOAP service
     */
    private final ConfluenceSoapService service;

    /**
     * Authentication token, obtained from
     * {@link ConfluenceSoapService#login(String,String)}
     */
    private final String token;

    private final RemoteServerInfo serverInfo;

    /**
     * Constructor
     *
     * @param service
     * @param token
     */
    /* package */ConfluenceSession(final ConfluenceSoapService service, final String token,
            final RemoteServerInfo info) {
        this.service = service;
        this.token = token;
        this.serverInfo = info;
    }

    /**
     * Get server info
     *
     * @return {@link RemoteServerInfo} instance
     * @throws RemoteException
     */
    public RemoteServerInfo getServerInfo() {
        return this.serverInfo;
    }

    /**
     * Get a Space by key name
     *
     * @param spaceKey
     * @return {@link RemoteSpace} instance
     * @throws RemoteException
     */
    public RemoteSpace getSpace(String spaceKey) throws RemoteException {
        return this.service.getSpace(this.token, spaceKey);
    }

    /**
     * Get a Page by Space and Page key names
     *
     * @param spaceKey
     * @param pageKey
     * @return {@link RemotePage} instance
     * @throws RemoteException
     * @throws UnsupportedOperationException if attempting to call this method
     *             against a 4.0 or newer server
     * @deprecated Calling this method on a Confluence 4.0+ server will result
     *             in a RemoteException
     */
    @Deprecated
    public RemotePage getPage(String spaceKey, String pageKey) throws RemoteException {
        if (isVersion4()) {
            // This v1 API is broken in Confluence 4.0 and newer.
            throw new UnsupportedOperationException(
                    "This API is not supported on Confluence version 4.0 and newer.  Use getPageSummary()");
        }

        return this.service.getPage(this.token, spaceKey, pageKey);
    }

    /**
     * This method is an attempt to bridge the gap between the deprecated v1
     * APIs and the as-yet unimplemented v2 APIs. The v1 getPage() API no longer
     * works on version 4.0+ servers, but the v2 getPage() does. The v1
     * getPageSummary() is the same functionality as getPage(), minus the
     * existing page content.
     *
     * @param spaceKey
     * @param pageKey
     * @return
     * @throws RemoteException
     */
    public RemotePageSummary getPageSummary(final String spaceKey, final String pageKey)
            throws RemoteException {
        if (!isVersion4()) {
            // This method did not exist in the pre-4.0 v1 SOAP API
            return this.getPage(spaceKey, pageKey);
        }

        return this.service.getPageSummary(this.token, spaceKey, pageKey);
    }

    public RemotePage storePage(final RemotePage page) throws RemoteException {
        return this.service.storePage(this.token, page);
    }

    public RemotePage updatePage(final RemotePage page, final RemotePageUpdateOptions options)
            throws RemoteException {
        return this.service.updatePage(this.token, page, options);
    }

    /**
     * Get all attachments for a page
     *
     * @param pageId
     * @return Array of {@link RemoteAttachment}s
     * @throws RemoteException
     */
    public RemoteAttachment[] getAttachments(long pageId) throws RemoteException {
        return this.service.getAttachments(this.token, pageId);
    }

    /**
     * Attach the file
     *
     * @param pageId
     * @param fileName
     * @param contentType
     * @param comment
     * @param bytes
     * @return {@link RemoteAttachment} instance that was created on the server
     * @throws RemoteException
     */
    public RemoteAttachment addAttachment(long pageId, String fileName, String contentType,
            String comment, byte[] bytes) throws RemoteException {
        RemoteAttachment attachment = new RemoteAttachment();
        attachment.setPageId(pageId);
        attachment.setFileName(sanitizeFileName(fileName));
        attachment.setFileSize(bytes.length);
        attachment.setContentType(contentType);
        attachment.setComment(comment);
        return this.service.addAttachment(this.token, attachment, bytes);
    }

    /**
     * Attach the file
     *
     * @param pageId
     * @param file
     * @param contentType
     * @param comment
     * @return {@link RemoteAttachment} instance
     * @throws IOException
     * @throws InterruptedException
     */
    public RemoteAttachment addAttachment(long pageId, FilePath file, String contentType,
            String comment) throws IOException, InterruptedException {
        ByteArrayOutputStream baos;
        baos = new ByteArrayOutputStream((int) file.length());
        file.copyTo(baos);
        byte[] data = baos.toByteArray();
        return addAttachment(pageId, file.getName(), contentType, comment, data);
    }

    /**
     * Attach the file
     *
     * @param pageId
     * @param file
     * @param contentType
     * @param comment
     * @return {@link RemoteAttachment} instance
     * @throws IOException
     * @throws FileNotFoundException
     */
    public RemoteAttachment addAttachment(long pageId, File file, String contentType, String comment)
            throws FileNotFoundException, IOException {
        final int len = (int) file.length();

        final FileChannel in = new FileInputStream(file).getChannel();

        final ByteArrayOutputStream baos = new ByteArrayOutputStream(len);
        final WritableByteChannel out = Channels.newChannel(baos);

        try {
            // Copy
            in.transferTo(0, len, out);
        } finally {
            // Clean up
            out.close();
            in.close();
        }

        final byte[] data = baos.toByteArray();

        return addAttachment(pageId, file.getName(), contentType, comment, data);
    }

    /**
     * Sanitize the attached filename, per Confluence restrictions
     *
     * @param fileName
     * @return
     */
    public static String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        return hudson.Util.fixEmptyAndTrim(fileName.replace('+', '_').replace('&', '_'));
    }

    /**
     * Returns true if this server is version 4.0 or newer.
     *
     * @return
     */
    public boolean isVersion4() {
        return this.serverInfo.getMajorVersion() >= 4;
    }
}
