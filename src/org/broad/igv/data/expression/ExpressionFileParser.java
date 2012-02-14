/*
 * Copyright (c) 2007-2011 by The Broad Institute of MIT and Harvard.  All Rights Reserved.
 *
 * This software is licensed under the terms of the GNU Lesser General Public License (LGPL),
 * Version 2.1 which is available at http://www.opensource.org/licenses/lgpl-2.1.php.
 *
 * THE SOFTWARE IS PROVIDED "AS IS." THE BROAD AND MIT MAKE NO REPRESENTATIONS OR
 * WARRANTES OF ANY KIND CONCERNING THE SOFTWARE, EXPRESS OR IMPLIED, INCLUDING,
 * WITHOUT LIMITATION, WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE, NONINFRINGEMENT, OR THE ABSENCE OF LATENT OR OTHER DEFECTS, WHETHER
 * OR NOT DISCOVERABLE.  IN NO EVENT SHALL THE BROAD OR MIT, OR THEIR RESPECTIVE
 * TRUSTEES, DIRECTORS, OFFICERS, EMPLOYEES, AND AFFILIATES BE LIABLE FOR ANY DAMAGES
 * OF ANY KIND, INCLUDING, WITHOUT LIMITATION, INCIDENTAL OR CONSEQUENTIAL DAMAGES,
 * ECONOMIC DAMAGES OR INJURY TO PROPERTY AND LOST PROFITS, REGARDLESS OF WHETHER
 * THE BROAD OR MIT SHALL BE ADVISED, SHALL HAVE OTHER REASON TO KNOW, OR IN FACT
 * SHALL KNOW OF THE POSSIBILITY OF THE FOREGOING.
 */
/*
 * ExpressionFileParser.java
 *
 * Parser for GCT and related file formats (e.g. RES).
 *
 * Created on October 18, 2007, 2:33 PM
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 */
package org.broad.igv.data.expression;

//~--- non-JDK imports --------------------------------------------------------

import org.apache.log4j.Logger;
import org.broad.igv.Globals;
import org.broad.igv.exceptions.ParserException;
import org.broad.igv.exceptions.ProbeMappingException;
import org.broad.igv.feature.Locus;
import org.broad.igv.feature.genome.Genome;
import org.broad.igv.tools.StatusMonitor;
import org.broad.igv.track.TrackType;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.util.MagetabSignalDialog;
import org.broad.igv.util.ParsingUtils;
import org.broad.igv.util.ResourceLocator;
import org.broad.tribble.readers.AsciiLineReader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

/**
 * TODO -- handle case with probe file
 *
 * @author jrobinso
 */
public class ExpressionFileParser {

    private static Logger log = Logger.getLogger(ExpressionFileParser.class);

    public enum FileType {

        RES, GCT, MAPPED, TAB, MET, DCHIP, MAGE_TAB
    }

    ResourceLocator dataFileLocator;

    FileType type;
    Genome genome;

    /**
     * Map chr -> longest feature
     */
    Map<String, Integer> longestProbeMap;

    Map<String, List<Row>> rowMap = new HashMap();
    StatusMonitor statusMonitor;
    GeneToLocusHelper locusHelper;

    // For effecient lookup, data column name -> index
    Map<String, Integer> dataColumnIndexMap;


    /**
     * Test to determine if the path referes to a GCT file.  The GCT specification requires that the first line
     * be equal to #1.2
     *
     * @param path
     * @return
     * @throws IOException
     */
    public static boolean isGCT(String path) throws IOException {
        BufferedReader reader = null;
        try {
            reader = ParsingUtils.openBufferedReader(path);
            String firstLine = reader.readLine();
            return firstLine != null && firstLine.trim().equals("#1.2");
        } finally {
            if (reader != null) reader.close();
        }
    }


    public ExpressionFileParser(ResourceLocator resFile, String probeFile, Genome genome) throws IOException {

        this.dataFileLocator = resFile;
        this.type = determineType(resFile);
        this.genome = genome;
        longestProbeMap = new HashMap();
        locusHelper = new GeneToLocusHelper(probeFile);

    }


    public ExpressionFileParser(File resFile, String probeFile, Genome genome) throws IOException {
        this(new ResourceLocator(resFile.getAbsolutePath()), probeFile, genome);
    }

    /*
     */

    public static boolean parsableMAGE_TAB(ResourceLocator file) throws IOException {
        AsciiLineReader reader = null;
        try {
            reader = ParsingUtils.openAsciiReader(file);
            String nextLine = null;

            //skip first row
            reader.readLine();

            //check second row for MAGE_TAB identifiers
            if ((nextLine = reader.readLine()) != null && (nextLine.contains("Reporter REF") ||
                    nextLine.contains("Composite Element REF") || nextLine.contains("Term Source REF") ||
                    nextLine.contains("CompositeElement REF") || nextLine.contains("TermSource REF") ||
                    nextLine.contains("Coordinates REF"))) {
                return true;
            }
        } finally {
            if (reader != null) {
                reader.close();
            }
        }

        return false;
    }

    public ExpressionDataset createDataset() {
        ExpressionDataset dataset = new ExpressionDataset(genome);
        parse(dataset);
        return dataset;
    }

    public static FileType determineType(ResourceLocator dataFileLocator) {
        String fn = dataFileLocator.getPath().toLowerCase();
        if (fn.endsWith(".txt") || fn.endsWith(".tab") || fn.endsWith(".xls") || fn.endsWith(".gz")) {
            fn = fn.substring(0, fn.lastIndexOf("."));
        }
        //TODO genomespace hack
        if (dataFileLocator.getPath().contains("?") && dataFileLocator.getPath().contains("dataformat/gct")) {
            fn = ".gct";
        }
        FileType type;

        if (fn.endsWith("res")) {
            type = FileType.RES;
        } else if (fn.endsWith("gct")) {
            type = FileType.GCT;
        } else if (fn.endsWith("mapped")) {
            type = FileType.MAPPED;
        } else if (fn.endsWith("met")) {
            type = FileType.MET;
        } else if (fn.endsWith("dchip")) {
            type = FileType.DCHIP;
        } else if ("mage-tab".equals(dataFileLocator.getType()) || "MAGE_TAB".equals(dataFileLocator.getDescription())) {
            type = FileType.MAGE_TAB;
        } else {
            type = FileType.TAB;
        }
        return type;
    }

    /**
     * Parse the file and return a Dataset
     *
     * @return
     */
    public void parse(ExpressionDataset dataset) {

        // Create a buffer for the string split utility.  We use  a custom utility as opposed
        // to String.split() for performance.

        dataset.setType(TrackType.GENE_EXPRESSION);

        BufferedReader reader = null;
        String nextLine = null;
        int lineCount = 0;
        //String[] columnHeadings = null;
        try {

            reader = ParsingUtils.openBufferedReader(dataFileLocator);

            // Parse the header(s) to determine the precise format.
            FormatDescriptor formatDescriptor = parseHeader(reader, type, dataset);
            final int probeColumn = formatDescriptor.probeColumn;
            final int descriptionColumn = formatDescriptor.descriptionColumn;
            int nDataColumns = formatDescriptor.dataColumns.length;

            dataset.setColumnHeadings(formatDescriptor.dataHeaders);

            dataColumnIndexMap = new HashMap<String, Integer>();
            for (int i = 0; i < formatDescriptor.dataHeaders.length; i++) {
                dataColumnIndexMap.put(formatDescriptor.dataHeaders[i], i);
            }

            // Loop through the data rows

            String[] tokens = new String[formatDescriptor.totalColumnCount];
            while ((nextLine = reader.readLine()) != null) {

                int nTokens = ParsingUtils.split(nextLine, tokens, '\t');
                String probeId = new String(tokens[probeColumn]);
                float[] values = new float[nDataColumns];

                String description = (descriptionColumn >= 0) ? tokens[descriptionColumn] : null;

                if (type == FileType.MAGE_TAB && probeId.startsWith("cg")) {
                    // TODO -- this is a very ugly and fragile method to determine data type! Change this!
                    dataset.setType(TrackType.DNA_METHYLATION);
                }

                for (int i = 0; i < nDataColumns; i++) {
                    try {
                        int dataIndex = formatDescriptor.dataColumns[i];

                        // If we are out of value tokens, or the cell is blank, assign NAN to the cell.
                        if ((dataIndex >= nTokens) || (tokens[dataIndex].length() == 0)) {
                            values[i] = Float.NaN;
                        } else {
                            values[i] = Float.parseFloat(tokens[dataIndex]);
                        }

                    } catch (NumberFormatException numberFormatException) {

                        // This s an expected condition.  IGV uses NaN to
                        // indicate non numbers (missing data values)
                        values[i] = Float.NaN;
                    }

                }
                addRow(probeId, description, values);
                lineCount++;

                // This method is designed to be interruptable (canceled by
                // user.  Check every 1000 lines for an interrupt.
                if (lineCount == 1000) {
                    checkForInterrupt();
                    lineCount = 0;
                    if (statusMonitor != null) {
                        statusMonitor.incrementPercentComplete(1);
                    }
                }
            }    // End loop through lines


            // Sort row for each chromosome by start location
            sortRows();

            // Update dataset
            for (String chr : rowMap.keySet()) {
                dataset.setStartLocations(chr, getStartLocations(chr));
                dataset.setEndLocations(chr, getEndLocations(chr));
                dataset.setFeatureNames(chr, getProbes(chr));


                for (String heading : formatDescriptor.dataHeaders) {
                    dataset.setData(heading, chr, getData(heading, chr));
                }
            }

            dataset.setLongestFeatureMap(longestProbeMap);

            if ((dataset == null) || dataset.isEmpty()) {
                String genomeId = genome == null ? "" : genome.getId();
                throw new ProbeMappingException(dataFileLocator.getPath(), genomeId);
            }

        } catch (FileNotFoundException ex) {
            throw new RuntimeException(ex);
        } catch (InterruptedException e) {
            throw new RuntimeException("Operation cancelled");
        } catch (Exception e) {
            e.printStackTrace();
            if (nextLine != null && lineCount != 0) {
                throw new ParserException(e.getMessage(), e, lineCount, nextLine);
            } else {
                throw new RuntimeException(e);
            }
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {

                }
            }
        }

    }


    public void addRow(String probeId, String description, float[] values) {
        List<Locus> loci = locusHelper.getLoci(probeId, description, genome.getId());
        if (loci != null) {
            for (Locus locus : loci) {
                if ((locus != null) && locus.isValid()) {
                    List<Row> rows = rowMap.get(locus.getChr());
                    if (rows == null) {
                        rows = new ArrayList();
                        rowMap.put(locus.getChr(), rows);
                    }

                    String chr = locus.getChr();
                    int length = locus.getEnd() - locus.getStart();
                    if (longestProbeMap.containsKey(chr)) {
                        longestProbeMap.put(chr, Math.max(longestProbeMap.get(chr), length));
                    } else {
                        longestProbeMap.put(chr, length);
                    }

                    rows.add(new Row(probeId, locus.getChr(), locus.getStart(), locus.getEnd(), values));

                }
            }
        }
    }


    /**
     * Sort all row collections by ascending start location
     */
    private void sortRows() {
        Comparator<Row> c = new Comparator<Row>() {

            public int compare(ExpressionFileParser.Row arg0, ExpressionFileParser.Row arg1) {
                return arg0.start - arg1.start;
            }
        };
        for (List<Row> rows : rowMap.values()) {
            Collections.sort(rows, c);
        }
    }

    public String[] getProbes(String chr) {
        List<Row> rows = rowMap.get(chr);
        String[] labels = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            labels[i] = rows.get(i).feature;
        }
        return labels;

    }


    public int[] getStartLocations(String chr) {

        List<Row> rows = rowMap.get(chr);
        int[] startLocations = new int[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            startLocations[i] = rows.get(i).start;
        }
        return startLocations;
    }


    public int[] getEndLocations(String chr) {

        List<Row> rows = rowMap.get(chr);
        int[] endLocations = new int[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            endLocations[i] = rows.get(i).end;
        }
        return endLocations;
    }


    public float[] getData(String heading, String chr) {

        int columnIndex = dataColumnIndexMap.get(heading);
        List<Row> rows = rowMap.get(chr);
        float[] data = new float[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            data[i] = rows.get(i).values[columnIndex];
        }
        return data;

    }


    /**
     * Note:  This is an exact copy of the method in IGVDatasetParser.  Refactor to merge these
     * two parsers, or share a common base class.
     *
     * @param comment
     * @param dataset
     */
    private static void parseComment(String comment, ExpressionDataset dataset) {

        // If no dataset is supplied there is nothing to do
        if (dataset == null) return;

        String tmp = comment.substring(1, comment.length());
        if (tmp.startsWith("track")) {
            dataset.setTrackLine(tmp);

        } else {
            String[] tokens = tmp.split("=");
            if (tokens.length != 2) {
                return;
            }
            String key = tokens[0].trim().toLowerCase();
            if (key.equals("name")) {
                dataset.setName(tokens[1].trim());
            } else if (key.equals("type")) {

                try {
                    dataset.setType(TrackType.valueOf(tokens[1].trim().toUpperCase()));
                } catch (Exception exception) {
                    // Ignore
                }
            }
        }
    }

    private void checkForInterrupt() throws InterruptedException {
        Thread.sleep(1);    // <- check for interrupted thread
    }

    public static FormatDescriptor parseHeader(BufferedReader reader, FileType type, ExpressionDataset dataset) throws IOException {


        int descriptionColumn = -1;    // Default - no description column
        int dataStartColumn = -1;
        int probeColumn = 0;

        switch (type) {
            case RES:
                dataStartColumn = 2;
                probeColumn = 1;
                descriptionColumn = 0;
                break;
            case GCT:
                dataStartColumn = 2;
                probeColumn = 0;
                descriptionColumn = 1;
                break;
            case MAPPED:
                dataStartColumn = 4;
                probeColumn = 0;
                break;
            case MET:
                dataStartColumn = 4;
                probeColumn = 0;
                break;
            case DCHIP:
                dataStartColumn = 1;
                probeColumn = 0;
                descriptionColumn = -1;
                break;
            case MAGE_TAB:
                descriptionColumn = -1;
                probeColumn = 0;
                break;
            case TAB:
                dataStartColumn = 1;
                probeColumn = 0;
            default:
                // throw exception?
        }


        String headerLine = findHeaderLine(reader, type, dataset);
        String[] firstHeaderRowTokens = headerLine.split("\t");

        List<String> dataHeadingList = new ArrayList(firstHeaderRowTokens.length);
        List<Integer> dataColumnList = new ArrayList<Integer>(firstHeaderRowTokens.length);
        if (type != FileType.MAGE_TAB) {
            int skip = type == FileType.RES ? 2 : 1;
            for (int i = dataStartColumn; i < firstHeaderRowTokens.length; i += skip) {
                String heading = firstHeaderRowTokens[i].replace('\"', ' ').trim();
                dataHeadingList.add(heading);
                dataColumnList.add(i);
            }
        } else {
            //Mage-Tab
            String nextLine = reader.readLine();
            String[] secondHeaderRowTokens = nextLine.split("\t");

            // Remove the label columns.
            for (int i = 1; i < firstHeaderRowTokens.length; i++) {
                if (firstHeaderRowTokens[i].trim().length() > 0) {
                    dataStartColumn = i;
                    break;
                }
            }

            String[] dataHeaderColumns = new String[secondHeaderRowTokens.length - dataStartColumn];
            System.arraycopy(secondHeaderRowTokens, dataStartColumn, dataHeaderColumns, 0, dataHeaderColumns.length);
            String qCol = findSignalColumnHeading(dataHeaderColumns);
            for (int i = 0; i < secondHeaderRowTokens.length; i++) {
                String heading = secondHeaderRowTokens[i].replace('\"', ' ').trim();
                if (heading.contains(qCol)) {
                    dataHeadingList.add(firstHeaderRowTokens[i]);
                    dataColumnList.add(i);
                }
            }
        }
        String[] dataHeaders = dataHeadingList.toArray(new String[dataHeadingList.size()]);
        int[] dataColumns = new int[dataColumnList.size()];
        for (int i = 0; i < dataColumnList.size(); i++) dataColumns[i] = dataColumnList.get(i);

        //nColumns = dataHeadings.length;

        // If format is RES skip the two lines following the header
        if (type == FileType.RES) {
            reader.readLine();
            reader.readLine();
        }

        return new FormatDescriptor(probeColumn, descriptionColumn, dataColumns, dataHeaders, firstHeaderRowTokens.length);
    }

    private static String findHeaderLine(BufferedReader reader, FileType type, ExpressionDataset dataset) throws IOException {
        String nextLine;
        String headerLine;
        if (type == FileType.GCT) {
            nextLine = reader.readLine();
            if (nextLine.startsWith("#")) {
                parseComment(nextLine, dataset);
            }
            nextLine = reader.readLine();
            if (nextLine.startsWith("#")) {
                parseComment(nextLine, dataset);
            }
            headerLine = reader.readLine();
        } else if (type != FileType.MAGE_TAB) {
            // Skip meta data, if any
            while ((nextLine = reader.readLine()).startsWith("#") && (nextLine != null)) {
                parseComment(nextLine, dataset);
            }
            headerLine = nextLine;
        } else {
            headerLine = reader.readLine();
        }
        return headerLine;
    }

    private static String findSignalColumnHeading(String[] tokens) {

        Set<String> columnHeaderSet = new HashSet<String>(Arrays.asList(tokens));

        String qCol = null;
        if (columnHeaderSet.contains("Beta_Value")) {
            qCol = "Beta_Value";
        } else if (columnHeaderSet.contains("Beta value")) {
            qCol = "Beta value";
        } else if (columnHeaderSet.contains("log2 Signal")) {
            qCol = "log2 Signal";
        } else if (columnHeaderSet.contains("Signal")) {
            qCol = "Signal";
        } else if (columnHeaderSet.contains("signal")) {
            qCol = "signal";
        } else if (!Globals.isHeadless()) {
            // Let user choose the signal column
            HashSet<String> uniqueColumns = new HashSet<String>(Arrays.asList(tokens));
            List<String> qColumns = new ArrayList<String>(uniqueColumns);
            if (qColumns.size() == 1) {
                qCol = qColumns.get(0);
            } else {
                Collections.sort(qColumns);

                MagetabSignalDialog msDialog = new MagetabSignalDialog(IGV.getMainFrame(), (String[]) qColumns.toArray(new String[0]));
                msDialog.setVisible(true);

                if (!msDialog.isCanceled()) {
                    qCol = msDialog.getQuantitationColumn();
                } else {
                    throw new RuntimeException("Could not find any signal columns in the MAGE-TAB file");
                }
            }

        }
        return qCol;
    }

    class Row {

        String feature;
        String chr;
        int start;
        int end;
        float[] values;

        Row(String feature, String chr, int start, int end, float[] values) {
            this.feature = feature;
            this.chr = chr;
            this.start = start;
            this.end = end;
            this.values = values;
        }
    }

    public static class FormatDescriptor {
        private int probeColumn;
        private int descriptionColumn;
        private int[] dataColumns;
        private String[] dataHeaders;
        private int totalColumnCount;

        FormatDescriptor(int probeColumn, int descriptionColumn, int[] dataColumns, String[] dataHeaders, int totalColumnCount) {
            this.probeColumn = probeColumn;
            this.descriptionColumn = descriptionColumn;
            this.dataColumns = dataColumns;
            this.dataHeaders = dataHeaders;
            this.totalColumnCount = totalColumnCount;
        }

        public int getProbeColumn() {
            return probeColumn;
        }

        public int getDescriptionColumn() {
            return descriptionColumn;
        }

        public int[] getDataColumns() {
            return dataColumns;
        }

        public String[] getDataHeaders() {
            return dataHeaders;
        }

        public int getTotalColumnCount() {
            return totalColumnCount;
        }
    }
}
