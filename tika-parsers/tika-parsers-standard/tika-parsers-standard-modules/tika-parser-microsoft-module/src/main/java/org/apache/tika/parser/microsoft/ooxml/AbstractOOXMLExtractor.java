/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tika.parser.microsoft.ooxml;

import static org.apache.tika.sax.XHTMLContentHandler.XHTML;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ooxml.POIXMLDocument;
import org.apache.poi.ooxml.extractor.POIXMLTextExtractor;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.PackageRelationshipCollection;
import org.apache.poi.openxml4j.opc.PackageRelationshipTypes;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.openxml4j.opc.internal.FileHelper;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.Ole10Native;
import org.apache.poi.poifs.filesystem.Ole10NativeException;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xssf.usermodel.XSSFRelation;
import org.apache.poi.xwpf.usermodel.XWPFRelation;
import org.apache.xmlbeans.XmlException;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.EmbeddedDocumentUtil;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.Office;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.OfficeParser;
import org.apache.tika.parser.microsoft.OfficeParser.POIFSDocumentType;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.parser.microsoft.SummaryExtractor;
import org.apache.tika.sax.EmbeddedContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;
import org.apache.tika.utils.ExceptionUtils;
import org.apache.tika.utils.StringUtils;
import org.apache.tika.utils.XMLReaderUtils;

/**
 * Base class for all Tika OOXML extractors.
 * <p/>
 * Tika extractors decorate POI extractors so that the parsed content of
 * documents is returned as a sequence of XHTML SAX events. Subclasses must
 * implement the buildXHTML method {@link #buildXHTML(XHTMLContentHandler)} that
 * populates the {@link XHTMLContentHandler} object received as parameter.
 */
public abstract class AbstractOOXMLExtractor implements OOXMLExtractor {


    static final String RELATION_AUDIO =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/audio";
    static final String RELATION_MEDIA =
            "http://schemas.microsoft.com/office/2007/relationships/media";
    static final String RELATION_VIDEO =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/video";
    static final String RELATION_DIAGRAM_DATA =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/diagramData";

    static final String RELATION_ALTERNATE_FORMAT_CHUNK =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/aFChunk";

    protected static final String[] EMBEDDED_RELATIONSHIPS =
            new String[]{RELATION_AUDIO, PackageRelationshipTypes.IMAGE_PART,
                    POIXMLDocument.PACK_OBJECT_REL_TYPE, PackageRelationshipTypes.CORE_DOCUMENT,
                    RELATION_DIAGRAM_DATA};
    private static final String TYPE_OLE_OBJECT =
            "application/vnd.openxmlformats-officedocument.oleObject";


    private final EmbeddedDocumentExtractor embeddedExtractor;
    private final ParseContext context;
    protected OfficeParserConfig config;
    protected POIXMLTextExtractor extractor;

    public AbstractOOXMLExtractor(ParseContext context, POIXMLTextExtractor extractor) {
        this.context = context;
        this.extractor = extractor;
        embeddedExtractor = EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(context);

        // This has already been set by OOXMLParser's call to configure()
        // We can rely on this being non-null.
        this.config = context.get(OfficeParserConfig.class);
    }

    /**
     * @see org.apache.tika.parser.microsoft.ooxml.OOXMLExtractor#getDocument()
     */
    public POIXMLDocument getDocument() {
        return (POIXMLDocument) extractor.getDocument();
    }

    /**
     * @see org.apache.tika.parser.microsoft.ooxml.OOXMLExtractor#getMetadataExtractor()
     */
    public MetadataExtractor getMetadataExtractor() {
        return new MetadataExtractor(extractor);
    }

    ParseContext getParseContext() {
        return context;
    }
    /**
     * @see
     * org.apache.tika.parser.microsoft.ooxml.OOXMLExtractor#getXHTML(ContentHandler, Metadata,
     * ParseContext)
     */
    public void getXHTML(ContentHandler handler, Metadata metadata, ParseContext context)
            throws SAXException, XmlException, IOException, TikaException {
        XHTMLContentHandler xhtml = new XHTMLContentHandler(handler, metadata);
        xhtml.startDocument();

        buildXHTML(xhtml);

        // Now do any embedded parts
        handleEmbeddedParts(xhtml, metadata, getEmbeddedPartMetadataMap());

        // thumbnail
        handleThumbnail(xhtml, metadata);

        xhtml.endDocument();
    }

    protected Map<String, EmbeddedPartMetadata> getEmbeddedPartMetadataMap() {
        return Collections.emptyMap();
    }

    protected String getJustFileName(String desc) {
        int idx = desc.lastIndexOf('/');
        if (idx != -1) {
            desc = desc.substring(idx + 1);
        }
        idx = desc.lastIndexOf('.');
        if (idx != -1) {
            desc = desc.substring(0, idx);
        }

        return desc;
    }

    private void handleThumbnail(ContentHandler handler, Metadata metadata) throws SAXException {
        try {
            OPCPackage opcPackage = extractor.getPackage();
            for (PackageRelationship rel : opcPackage
                    .getRelationshipsByType(PackageRelationshipTypes.THUMBNAIL)) {
                PackagePart tPart = opcPackage.getPart(rel);
                if (tPart == null) {
                    continue;
                }
                InputStream tStream = tPart.getInputStream();
                Metadata thumbnailMetadata = new Metadata();
                String thumbName = tPart.getPartName().getName();
                thumbnailMetadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, thumbName);

                AttributesImpl attributes = new AttributesImpl();
                attributes.addAttribute(XHTML, "class", "class", "CDATA", "embedded");
                attributes.addAttribute(XHTML, "id", "id", "CDATA", thumbName);
                handler.startElement(XHTML, "div", "div", attributes);
                handler.endElement(XHTML, "div", "div");

                thumbnailMetadata.set(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID, thumbName);
                thumbnailMetadata.set(Metadata.CONTENT_TYPE, tPart.getContentType());
                thumbnailMetadata.set(TikaCoreProperties.TITLE, tPart.getPartName().getName());
                thumbnailMetadata.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE,
                        TikaCoreProperties.EmbeddedResourceType.THUMBNAIL.name());

                if (embeddedExtractor.shouldParseEmbedded(thumbnailMetadata)) {
                    embeddedExtractor.parseEmbedded(TikaInputStream.get(tStream),
                            new EmbeddedContentHandler(handler), thumbnailMetadata, false);
                }

                tStream.close();
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception ex) {
            WriteLimitReachedException.throwIfWriteLimitReached(ex);
            //swallow otherwise
            metadata.add(TikaCoreProperties.EMBEDDED_EXCEPTION,
                    ExceptionUtils.getStackTrace(ex));
        }
    }

    private void handleEmbeddedParts(XHTMLContentHandler xhtml, Metadata metadata,
                                     Map<String, EmbeddedPartMetadata> embeddedPartMetadataMap)
            throws TikaException, IOException, SAXException {
        //keep track of media items that have been handled
        //there can be multiple relationships pointing to the
        //same underlying media item.  We only want to process
        //the underlying media item once.
        Set<String> handledTarget = new HashSet<>();
        try {
            for (PackagePart source : getMainDocumentParts()) {
                if (source == null) {
                    //parts can go missing; silently ignore --  TIKA-2134
                    continue;
                }
                for (PackageRelationship rel : source.getRelationships()) {
                    try {
                        handleEmbeddedPart(source, rel, xhtml, metadata,
                                embeddedPartMetadataMap, handledTarget);
                    } catch (SAXException | SecurityException e) {
                        throw e;
                    } catch (Exception e) {
                        EmbeddedDocumentUtil.recordEmbeddedStreamException(e, metadata);
                    }
                }
            }
        } catch (InvalidFormatException e) {
            throw new TikaException("Broken OOXML file", e);
        }
    }

    private void handleEmbeddedPart(PackagePart source, PackageRelationship rel,
                                    XHTMLContentHandler xhtml, Metadata parentMetadata,
                                    Map<String, EmbeddedPartMetadata> embeddedPartMetadataMap,
                                    Set<String> handledTarget)
            throws IOException, SAXException, TikaException, InvalidFormatException {
        URI targetURI = rel.getTargetURI();
        if (targetURI != null) {
            if (handledTarget.contains(targetURI.toString())) {
                return;
            }
        }

        URI sourceURI = rel.getSourceURI();
        String sourceDesc;
        if (sourceURI != null) {
            sourceDesc = getJustFileName(sourceURI.getPath());
            if (sourceDesc.startsWith("slide")) {
                sourceDesc += "_";
            } else {
                sourceDesc = "";
            }
        } else {
            sourceDesc = "";
        }
        if (rel.getTargetMode() != TargetMode.INTERNAL) {
            return;
        }
        PackagePart target;

        try {
            target = source.getRelatedPart(rel);
        } catch (IllegalArgumentException ex) {
            return;
        }
        EmbeddedPartMetadata embeddedPartMetadata = embeddedPartMetadataMap.get(rel.getId());
        String type = rel.getRelationshipType();
        if (POIXMLDocument.OLE_OBJECT_REL_TYPE.equals(type) &&
                TYPE_OLE_OBJECT.equals(target.getContentType())) {
            handleEmbeddedOLE(target, xhtml, sourceDesc + rel.getId(), parentMetadata,
                    embeddedPartMetadata);
            if (targetURI != null) {
                handledTarget.add(targetURI.toString());
            }
        } else if (PackageRelationshipTypes.IMAGE_PART.equals(type)) {
            handleEmbeddedFile(target, xhtml, sourceDesc + rel.getId(),
                    embeddedPartMetadata, TikaCoreProperties.EmbeddedResourceType.INLINE);
            if (targetURI != null) {
                handledTarget.add(targetURI.toString());
            }
        } else if (RELATION_MEDIA.equals(type) || RELATION_VIDEO.equals(type) ||
                RELATION_AUDIO.equals(type) ||
                POIXMLDocument.PACK_OBJECT_REL_TYPE.equals(type) ||
                POIXMLDocument.OLE_OBJECT_REL_TYPE.equals(type)) {
            handleEmbeddedFile(target, xhtml, sourceDesc + rel.getId(),
                    embeddedPartMetadata,
                    TikaCoreProperties.EmbeddedResourceType.ATTACHMENT);
            if (targetURI != null) {
                handledTarget.add(targetURI.toString());
            }
        } else if (XSSFRelation.VBA_MACROS.getRelation().equals(type)) {
            handleMacros(target, xhtml);
            if (targetURI != null) {
                handledTarget.add(targetURI.toString());
            }
        } else if (RELATION_ALTERNATE_FORMAT_CHUNK.equals(type)) {
            //TODO check for targetMode=INTERNAL?
            handleEmbeddedFile(target, xhtml, sourceDesc + rel.getId(),
                    embeddedPartMetadata,
                    TikaCoreProperties.EmbeddedResourceType.ALTERNATE_FORMAT_CHUNK);
            if (targetURI != null) {
                handledTarget.add(targetURI.toString());
            }
        }
    }


    /**
     * Handles an embedded OLE object in the document
     */
    private void handleEmbeddedOLE(PackagePart part, XHTMLContentHandler xhtml, String rel,
                                   Metadata parentMetadata,
                                   EmbeddedPartMetadata embeddedPartMetadata) throws IOException,
            SAXException {
        // A POIFSFileSystem needs to be at least 3 blocks big to be valid
        if (part.getSize() >= 0 && part.getSize() < 512 * 3) {
            // Too small, skip
            return;
        }

        InputStream is = part.getInputStream();
        // Open the POIFS (OLE2) structure and process
        POIFSFileSystem fs = null;
        try {
            fs = new POIFSFileSystem(part.getInputStream());
        } catch (Exception e) {
            EmbeddedDocumentUtil.recordEmbeddedStreamException(e, parentMetadata);
            return;
        }
        TikaInputStream stream = null;
        try {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE,
                    TikaCoreProperties.EmbeddedResourceType.ATTACHMENT.name());
            metadata.set(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID, rel);

            DirectoryNode root = fs.getRoot();
            POIFSDocumentType type = POIFSDocumentType.detectType(root);

            String packageEntryName = getPackageEntryName(root);
            try {
                SummaryExtractor summaryExtractor = new SummaryExtractor(metadata);
                summaryExtractor.parseSummaries(root);
            } catch (TikaException e) {
                //swallow -- things happened
            }
            if (packageEntryName != null) {
                //OLE 2.0
                updateMetadata(metadata, embeddedPartMetadata);

                stream = TikaInputStream.get(fs.createDocumentInputStream(packageEntryName));
                if (embeddedExtractor.shouldParseEmbedded(metadata)) {
                    embeddedExtractor
                            .parseEmbedded(stream, xhtml, metadata,
                                    true);
                }
            } else if (POIFSDocumentType.OLE10_NATIVE == type) {
                // TIKA-704: OLE 1.0 embedded document
                Ole10Native ole = Ole10Native.createFromEmbeddedOleObject(fs);
                if (ole.getLabel() != null) {
                    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, ole.getLabel());
                }
                if (ole.getCommand() != null) {
                    metadata.add(TikaCoreProperties.ORIGINAL_RESOURCE_NAME, ole.getCommand());
                }
                if (ole.getFileName() != null) {
                    metadata.add(TikaCoreProperties.ORIGINAL_RESOURCE_NAME, ole.getFileName());
                }
                byte[] data = ole.getDataBuffer();
                if (data != null) {
                    stream = TikaInputStream.get(data);
                }

                if (stream != null && embeddedExtractor.shouldParseEmbedded(metadata)) {
                    embeddedExtractor
                            .parseEmbedded(stream, xhtml, metadata,
                                    true);
                }
            } else {
                handleEmbeddedFile(part, xhtml, rel, embeddedPartMetadata,
                        TikaCoreProperties.EmbeddedResourceType.ATTACHMENT);
            }
        } catch (FileNotFoundException e) {
            // There was no CONTENTS entry, so skip this part
        } catch (Ole10NativeException e) {
            // Could not process an OLE 1.0 entry, so skip this part
        } catch (IOException e) {
            EmbeddedDocumentUtil.recordEmbeddedStreamException(e, parentMetadata);
        } finally {
            if (fs != null) {
                fs.close();
            }
            if (stream != null) {
                stream.close();
            }
        }
    }

    private void updateMetadata(Metadata metadata, EmbeddedPartMetadata embeddedPartMetadata) {
        if (embeddedPartMetadata == null) {
            return;
        }
        if (! StringUtils.isBlank(embeddedPartMetadata.getProgId())) {
            metadata.set(Office.PROG_ID, embeddedPartMetadata.getProgId());
        }
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, embeddedPartMetadata.getFullName());
    }

    private String getPackageEntryName(DirectoryNode root) {
        if (root.hasEntry("\u0001Ole")) {
            //we used to require this too: root.hasEntry("\u0001CompObj") before TIKA-3526
            if (root.hasEntry("Package")) {
                return "Package";
            } else if (root.hasEntry("CONTENTS")) {
                return "CONTENTS";
            } else if (root.hasEntry("package")) {
                return "package";
            }
        }
        if (root.hasEntry("package")) {
            return "package";
        }
        /*
            raw CorelDraw stream may be in an ole bundle
            but there can be other resources for the image
            in other streams under root...think about this...
            see: AZG2X4VXB3KIEDT3OVZC4R645KU5VSOF
        if (root.hasEntry("CorelDRAW")) {
            return "CorelDRAW";
        }*/
        return null;
    }

    /**
     * Handles an embedded file in the document
     */
    protected void handleEmbeddedFile(PackagePart part, XHTMLContentHandler xhtml,
                                      String rel,
                                      EmbeddedPartMetadata embeddedPartMetadata,
                                      TikaCoreProperties.EmbeddedResourceType embeddedResourceType)
            throws SAXException, IOException {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID, rel);
        metadata.set(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE,
                embeddedResourceType.name());

        // Get the name
        updateResourceName(part, embeddedPartMetadata, metadata);

        // Get the content type
        metadata.set(Metadata.CONTENT_TYPE, part.getContentType());

        // Call the recursing handler
        if (embeddedExtractor.shouldParseEmbedded(metadata)) {
            try (TikaInputStream tis = TikaInputStream.get(part.getInputStream())) {
                embeddedExtractor
                        .parseEmbedded(tis, xhtml, metadata, true);
            }
        }
    }

    private void updateResourceName(PackagePart part, EmbeddedPartMetadata embeddedPartMetadata,
                                    Metadata metadata) {

        if (embeddedPartMetadata != null) {
            if (! StringUtils.isBlank(embeddedPartMetadata.getProgId())) {
                metadata.set(Office.PROG_ID, embeddedPartMetadata.getProgId());
            }
            String fullName = embeddedPartMetadata.getFullName();
            if (!StringUtils.isBlank(fullName)) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fullName);
                return;
            }
        }
        //TODO -- should we record the literal name of the embedded file?
        String name = part.getPartName().getName();
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash > -1) {
            name = name.substring(lastSlash + 1);
        }
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
    }

    /**
     * Populates the {@link XHTMLContentHandler} object received as parameter.
     */
    protected abstract void buildXHTML(XHTMLContentHandler xhtml)
            throws SAXException, XmlException, IOException;

    /**
     * Return a list of the main parts of the document, used
     * when searching for embedded resources.
     * This should be all the parts of the document that end
     * up with things embedded into them.
     */
    protected abstract List<PackagePart> getMainDocumentParts() throws TikaException;


    void handleMacros(PackagePart macroPart, ContentHandler handler)
            throws TikaException, SAXException {
        OfficeParserConfig officeParserConfig = context.get(OfficeParserConfig.class);

        if (officeParserConfig.isExtractMacros()) {
            try (InputStream is = macroPart.getInputStream()) {
                try (POIFSFileSystem poifs = new POIFSFileSystem(is)) {
                    //Macro reading exceptions are already swallowed here
                    OfficeParser.extractMacros(poifs, handler, embeddedExtractor);
                }
            } catch (IOException e) {
                throw new TikaException("Broken OOXML file", e);
            }
        }
    }

    /**
     * This is used by the SAX docx and pptx decorators to load hyperlinks and
     * other linked objects
     *
     * @param bodyPart
     * @return
     */
    protected Map<String, String> loadLinkedRelationships(PackagePart bodyPart,
                                                          boolean includeInternal,
                                                          Metadata metadata) {
        Map<String, String> linkedRelationships = new HashMap<>();
        try {
            PackageRelationshipCollection prc =
                    bodyPart.getRelationshipsByType(XWPFRelation.HYPERLINK.getRelation());
            for (int i = 0; i < prc.size(); i++) {
                PackageRelationship pr = prc.getRelationship(i);
                if (pr == null) {
                    continue;
                }
                if (!includeInternal && TargetMode.INTERNAL.equals(pr.getTargetMode())) {
                    continue;
                }
                String id = pr.getId();
                String url = (pr.getTargetURI() == null) ? null : pr.getTargetURI().toString();
                if (id != null && url != null) {
                    linkedRelationships.put(id, url);
                }
            }

            for (String rel : EMBEDDED_RELATIONSHIPS) {

                prc = bodyPart.getRelationshipsByType(rel);
                for (int i = 0; i < prc.size(); i++) {
                    PackageRelationship pr = prc.getRelationship(i);
                    if (pr == null) {
                        continue;
                    }
                    String id = pr.getId();
                    String uriString =
                            (pr.getTargetURI() == null) ? null : pr.getTargetURI().toString();
                    String fileName = uriString;
                    if (pr.getTargetURI() != null) {
                        try {
                            fileName = FileHelper.getFilename(new File(fileName));
                        } catch (Exception e) {
                            fileName = uriString;
                        }
                    }
                    if (id != null) {
                        fileName = (fileName == null) ? "" : fileName;
                        linkedRelationships.put(id, fileName);
                    }
                }
            }

        } catch (InvalidFormatException e) {
            EmbeddedDocumentUtil.recordEmbeddedStreamException(e, metadata);
        }
        return linkedRelationships;
    }

    /**
     * This should handle the comments, master, notes, with the streaming "general docx/pptx
     * handler"
     *
     * @param contentType
     * @param xhtmlClassLabel
     * @param parentPart
     * @param contentHandler
     */
    void handleGeneralTextContainingPart(String contentType, String xhtmlClassLabel,
                                         PackagePart parentPart, Metadata parentMetadata,
                                         ContentHandler contentHandler) throws SAXException {

        PackageRelationshipCollection relatedPartPRC = null;

        try {
            relatedPartPRC = parentPart.getRelationshipsByType(contentType);
        } catch (InvalidFormatException e) {
            parentMetadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                    ExceptionUtils.getStackTrace(e));
        }
        if (relatedPartPRC != null && relatedPartPRC.size() > 0) {
            AttributesImpl attributes = new AttributesImpl();

            attributes.addAttribute("", "class", "class",
                    "CDATA", xhtmlClassLabel);
            contentHandler.startElement("", "div", "div", attributes);
            for (int i = 0; i < relatedPartPRC.size(); i++) {
                PackageRelationship relatedPartPackageRelationship =
                        relatedPartPRC.getRelationship(i);
                try {
                    PackagePart relatedPartPart =
                            parentPart.getRelatedPart(relatedPartPackageRelationship);
                    try (InputStream stream = relatedPartPart.getInputStream()) {
                        XMLReaderUtils.parseSAX(stream,
                                new EmbeddedContentHandler(contentHandler), context);

                    } catch (IOException | TikaException e) {
                        parentMetadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                                ExceptionUtils.getStackTrace(e));
                    }
                } catch (InvalidFormatException e) {
                    parentMetadata.add(TikaCoreProperties.TIKA_META_EXCEPTION_WARNING,
                            ExceptionUtils.getStackTrace(e));
                }
            }
            contentHandler.endElement("", "div", "div");
        }

    }

}
