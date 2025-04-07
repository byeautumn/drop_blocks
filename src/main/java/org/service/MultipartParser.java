package org.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MultipartParser {

    private final InputStream inputStream;
    private final String boundary;
    private byte[] buffer = new byte[8192];
    private int bufferLength = 0;
    private int bufferPosition = 0;

    public MultipartParser(InputStream inputStream, String contentType) throws IOException {
        this.inputStream = inputStream;
        String boundaryLine = null;
        if (contentType != null && contentType.startsWith("multipart/form-data")) {
            String[] parts = contentType.split(";");
            for (String part : parts) {
                part = part.trim();
                if (part.startsWith("boundary=")) {
                    boundaryLine = "--" + part.substring(part.indexOf('=') + 1).trim();
                    break;
                }
            }
        }
        if (boundaryLine == null) {
            throw new IOException("Invalid Content-Type for multipart/form-data or boundary not found");
        }
        this.boundary = boundaryLine;
        // Consume the first boundary line
        if (!readToBoundary()) {
            throw new IOException("Invalid multipart stream: missing initial boundary");
        }
        System.out.println("MultipartParser initialized with boundary: " + boundary);
    }

    public Part readNextPart() throws IOException {
        System.out.println("Reading next part. Buffer position: " + bufferPosition + ", Buffer length: " + bufferLength);
        if (bufferPosition >= bufferLength) {
            bufferLength = inputStream.read(buffer);
            bufferPosition = 0;
            if (bufferLength == -1) {
                System.out.println("End of stream reached.");
                return null; // End of stream
            }
            System.out.println("Read " + bufferLength + " bytes into buffer.");
        }

        ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
        while (true) {
            if (bufferPosition >= bufferLength) {
                bufferLength = inputStream.read(buffer);
                bufferPosition = 0;
                if (bufferLength == -1) {
                    System.out.println("Unexpected end of stream while reading header.");
                    return null; // Unexpected end of stream
                }
                System.out.println("Read more bytes for header: " + bufferLength);
            }
            byte b = buffer[bufferPosition++];
            headerBuffer.write(b);
            if (headerBuffer.size() > 4 &&
                    headerBuffer.toByteArray()[headerBuffer.size() - 4] == '\r' &&
                    headerBuffer.toByteArray()[headerBuffer.size() - 3] == '\n' &&
                    headerBuffer.toByteArray()[headerBuffer.size() - 2] == '\r' &&
                    headerBuffer.toByteArray()[headerBuffer.size() - 1] == '\n') {
                System.out.println("End of header reached.");
                break;
            }
        }

        String headerString = new String(headerBuffer.toByteArray(), StandardCharsets.UTF_8).trim();
        System.out.println("Part Header:\n" + headerString);
        if (headerString.isEmpty()) {
            System.out.println("Empty header, checking for next boundary.");
            // This might indicate the end of parts
            if (readToBoundary()) {
                return readNextPart();
            } else {
                System.out.println("No more parts found after empty header.");
                return null;
            }
        }

        Part part = parsePartHeader(headerString);
        return part;
    }

    private boolean readToBoundary() throws IOException {
        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        boolean crFound = false;
        while (true) {
            if (bufferPosition >= bufferLength) {
                bufferLength = inputStream.read(buffer);
                bufferPosition = 0;
                if (bufferLength == -1) {
                    System.out.println("End of stream while searching for boundary.");
                    return false; // End of stream without finding boundary
                }
                System.out.println("Read more bytes while searching for boundary: " + bufferLength);
            }
            byte b = buffer[bufferPosition++];
            if (b == '\r') {
                crFound = true;
            } else if (b == '\n' && crFound) {
                String line = new String(lineBuffer.toByteArray(), StandardCharsets.UTF_8);
                System.out.println("Read line: [" + line + "]");
                if (line.trim().equals(boundary)) {
                    System.out.println("Boundary found: " + boundary);
                    return true;
                }
                lineBuffer.reset();
                crFound = false;
            } else {
                lineBuffer.write(b);
                crFound = false;
            }
        }
    }

    private Part parsePartHeader(String header) {
        Part part = new Part(this);
        String[] headerLines = header.split("\\r\\n");
        for (String line : headerLines) {
            if (line.startsWith("Content-Disposition:")) {
                Pattern dispositionTypePattern = Pattern.compile("Content-Disposition: (\\w+)");
                Matcher dispositionTypeMatcher = dispositionTypePattern.matcher(line);
                if (dispositionTypeMatcher.find()) {
                    part.disposition = dispositionTypeMatcher.group(1);
                }

                Pattern attributePattern = Pattern.compile(";\\s*(\\w+)=\"([^\"]*)\"");
                Matcher attributeMatcher = attributePattern.matcher(line);
                while (attributeMatcher.find()) {
                    String attributeName = attributeMatcher.group(1);
                    String attributeValue = attributeMatcher.group(2);
                    if ("name".equalsIgnoreCase(attributeName)) {
                        part.name = attributeValue;
                    } else if ("filename".equalsIgnoreCase(attributeName)) {
                        part.filename = attributeValue;
                        part.isFile = true;
                    }
                }
                System.out.println("Content-Disposition: " + part.disposition + ", name: " + part.name + ", filename: " + part.filename + ", isFile: " + part.isFile);
            } else if (line.startsWith("Content-Type:")) {
                part.contentType = line.substring("Content-Type:".length()).trim();
                System.out.println("Content-Type: " + part.contentType);
            }
        }
        part.header = header;
        return part;
    }

    public class Part {
        private String header;
        private String disposition;
        private String name;
        private String filename;
        private String contentType;
        private boolean isFile;
        private ByteArrayOutputStream valueBuffer;
        private InputStream partInputStream;
        private final MultipartParser parent;

        public Part(MultipartParser parent) {
            this.parent = parent;
        }

        public String getName() {
            return name;
        }

        public String getFilename() {
            return filename;
        }

        public String getContentType() {
            return contentType;
        }

        public boolean isFilePart() {
            return isFile;
        }

        public boolean isParamPart() {
            return !isFile;
        }

        public byte[] getValueBytes() {
            if (valueBuffer != null) {
                return valueBuffer.toByteArray();
            }
            return new byte[0];
        }

        public InputStream getInputStream() {
            return new PartInputStream(parent);
        }
    }

    private class PartInputStream extends InputStream {
        private final MultipartParser parser;
        private boolean eof = false;

        public PartInputStream(MultipartParser parser) {
            this.parser = parser;
            System.out.println("PartInputStream created.");
        }

        @Override
        public int read() throws IOException {
            if (eof) {
                return -1;
            }
            if (parser.bufferPosition >= parser.bufferLength) {
                parser.bufferLength = parser.inputStream.read(parser.buffer);
                parser.bufferPosition = 0;
                if (parser.bufferLength == -1) {
                    System.out.println("PartInputStream: End of underlying stream.");
                    eof = true;
                    return -1;
                }
                System.out.println("PartInputStream: Read " + parser.bufferLength + " bytes.");
            }

            // Check for the next boundary
            if (parser.buffer[parser.bufferPosition] == '-' && parser.bufferPosition + parser.boundary.length() + 2 <= parser.bufferLength) {
                String potentialBoundary = new String(parser.buffer, parser.bufferPosition, parser.boundary.length() + 2, StandardCharsets.UTF_8);
                if (potentialBoundary.startsWith(parser.boundary)) {
                    System.out.println("PartInputStream: Boundary detected: " + potentialBoundary);
                    eof = true;
                    return -1;
                }
            }
            int result = parser.buffer[parser.bufferPosition++] & 0xFF;
            // System.out.println("PartInputStream: Read byte: " + result);
            return result;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (eof) {
                return -1;
            }
            if (parser.bufferPosition >= parser.bufferLength) {
                parser.bufferLength = parser.inputStream.read(parser.buffer);
                parser.bufferPosition = 0;
                if (parser.bufferLength == -1) {
                    System.out.println("PartInputStream: End of underlying stream (bulk read).");
                    eof = true;
                    return -1;
                }
                System.out.println("PartInputStream: Read " + parser.bufferLength + " bytes (bulk read).");
            }

            int bytesToRead = Math.min(len, parser.bufferLength - parser.bufferPosition);

            // Check for the next boundary within the read range
            for (int i = 0; i < bytesToRead; i++) {
                if (parser.buffer[parser.bufferPosition + i] == '-' && parser.bufferPosition + i + parser.boundary.length() + 2 <= parser.bufferLength) {
                    String potentialBoundary = new String(parser.buffer, parser.bufferPosition + i, parser.boundary.length() + 2, StandardCharsets.UTF_8);
                    if (potentialBoundary.startsWith(parser.boundary)) {
                        System.out.println("PartInputStream: Boundary detected (bulk read): " + potentialBoundary);
                        eof = true;
                        if (i > 0) {
                            System.arraycopy(parser.buffer, parser.bufferPosition, b, off, i);
                            parser.bufferPosition += i;
                            return i;
                        }
                        return -1;
                    }
                }
            }

            System.arraycopy(parser.buffer, parser.bufferPosition, b, off, bytesToRead);
            parser.bufferPosition += bytesToRead;
            return bytesToRead;
        }
    }
}