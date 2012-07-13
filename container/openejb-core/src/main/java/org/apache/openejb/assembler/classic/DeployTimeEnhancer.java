package org.apache.openejb.assembler.classic;

import org.apache.openejb.config.event.BeforeDeploymentEvent;
import org.apache.openejb.loader.Files;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.observer.Observes;
import org.apache.openejb.util.JarCreator;
import org.apache.openejb.util.JarExtractor;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.openejb.util.URLs;
import org.apache.xbean.finder.filter.Filter;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

public class DeployTimeEnhancer {
    private static final Logger LOGGER = Logger.getInstance(LogCategory.OPENEJB_DEPLOY, DeployTimeEnhancer.class);
    private static final SAXParserFactory SAX_PARSER_FACTORY = SAXParserFactory.newInstance();

    private static final String OPENEJB_JAR_ENHANCEMENT_INCLUDE = "openejb.jar.enhancement.include";
    private static final String OPENEJB_JAR_ENHANCEMENT_EXCLUDE = "openejb.jar.enhancement.exclude";

    private static final String CLASS_EXT = ".class";
    private static final String PROPERTIES_FILE_PROP = "propertiesFile";
    private static final String META_INF_PERSISTENCE_XML = "META-INF/persistence.xml";
    private static final String TMP_ENHANCEMENT_SUFFIX = ".tmp-enhancement";
    private static final String JAR_ENHANCEMENT_SUFFIX = "-enhanced";

    static {
        SAX_PARSER_FACTORY.setNamespaceAware(true);
        SAX_PARSER_FACTORY.setValidating(false);
    }

    private final Method enhancerMethod;
    private final Constructor<?> optionsConstructor;

    public DeployTimeEnhancer() {
        Method mtd;
        Constructor<?> cstr;
        final ClassLoader cl = DeployTimeEnhancer.class.getClassLoader();
        try {
            final Class<?> enhancerClass = cl.loadClass("org.apache.openjpa.enhance.PCEnhancer");
            final Class<?> arg2 = cl.loadClass("org.apache.openjpa.lib.util.Options");
            cstr = arg2.getConstructor(Properties.class);
            mtd = enhancerClass.getMethod("run", String[].class, arg2);
        } catch (Exception e) {
            LOGGER.warning("openjpa enhancer can't be found in the container, will be skipped");
            mtd = null;
            cstr = null;
        }
        optionsConstructor = cstr;
        enhancerMethod = mtd;
    }

    public void enhance(@Observes final BeforeDeploymentEvent event) {
        if (enhancerMethod == null) {
            LOGGER.debug("OpenJPA is not available so no deploy-time enhancement will be done");
            return;
        }

        // find persistence.xml
        final Map<String, List<String>> classesByPXml = new HashMap<String, List<String>>();
        final Filter filter = new AlreadyEnhancedFilter();
        for (URL url : event.getUrls()) {
            final File file = URLs.toFile(url);
            if (filter.accept(file.getName())) {
                if (file.isDirectory()) {
                    final String pXmls = getWarPersistenceXml(url);
                    if (pXmls != null) {
                        feed(classesByPXml, pXmls);
                    }
                } else if (file.getName().endsWith(".jar")) {
                    try {
                        final JarFile jar = new JarFile(file);
                        ZipEntry entry = jar.getEntry(META_INF_PERSISTENCE_XML);
                        if (entry != null) {
                            final String path = file.getAbsolutePath();
                            final File unpacked = new File(path.substring(0, path.length() - 4) + TMP_ENHANCEMENT_SUFFIX);
                            JarExtractor.extract(file, unpacked);
                            feed(classesByPXml, new File(unpacked, META_INF_PERSISTENCE_XML).getAbsolutePath());
                        }
                    } catch (IOException e) {
                        // ignored
                    }
                }
            }
        }

        // enhancement
        for (Map.Entry<String, List<String>> entry : classesByPXml.entrySet()) {
            final Properties opts = new Properties();
            opts.setProperty(PROPERTIES_FILE_PROP, entry.getKey());

            final Object optsArg;
            try {
                optsArg = optionsConstructor.newInstance(opts);
            } catch (Exception e) {
                LOGGER.debug("can't create options for enhancing");
                return;
            }

            LOGGER.info("enhancing url(s): " + Arrays.asList(event.getUrls()));
            try {
                enhancerMethod.invoke(null, toFilePaths(entry.getValue()), optsArg);
            } catch (Exception e) {
                LOGGER.warning("can't enhanced at deploy-time entities", e);
            }
        }

        // clean up extracted jars
        for (Map.Entry<String, List<String>> entry : classesByPXml.entrySet()) {
            final List<String> values = entry.getValue();
            for (String rawPath : values) {
                if (rawPath.endsWith(TMP_ENHANCEMENT_SUFFIX)) {
                    final String path = rawPath.substring(0, rawPath.length() - TMP_ENHANCEMENT_SUFFIX.length());
                    final File dir = new File(path);
                    final File file = new File(path + ".jar");
                    if (file.exists()) {
                        try {
                            JarCreator.jarDir(dir, new File(dir.getParentFile(), dir.getName() + JAR_ENHANCEMENT_SUFFIX + ".jar"));
                            Files.delete(file); // don't delete if any exception is thrown
                        } catch (IOException e) {
                            LOGGER.error("can't repackage enhanced jar file " + file.getName());
                        }
                        Files.delete(dir);
                    }
                }
            }
            values.clear();
        }

        classesByPXml.clear();
    }

    private void feed(final Map<String, List<String>> classesByPXml, final String pXml) {
        final List<String> paths = new ArrayList<String>();

        // first add the classes directory where is the persistence.xml
        if (pXml.endsWith(META_INF_PERSISTENCE_XML)) {
            paths.add(pXml.substring(0, pXml.length() - META_INF_PERSISTENCE_XML.length()));
        } else if (pXml.endsWith("/WEB-INF/persistence.xml")) {
            paths.add(pXml.substring(0, pXml.length() - 24));
        }

        // then jar-file
        try {
            final SAXParser parser = SAX_PARSER_FACTORY.newSAXParser();
            final JarFileParser handler = new JarFileParser();
            parser.parse(new File(pXml), handler);
            for (String path : handler.getPaths()) {
                paths.add(relative(paths.iterator().next(), path));
            }
        } catch (Exception e) {
            LOGGER.error("can't parse '" + pXml + "'", e);
        }

        classesByPXml.put(pXml, paths);
    }

    // relativePath = relative path to the jar file containing the persistence.xml
    private String relative(final String relativePath, final String pXmlPath) {
        return new File(new File(pXmlPath).getParent(), relativePath).getAbsolutePath();
    }

    private String getWarPersistenceXml(final URL url) {
        final File dir = URLs.toFile(url);
        if (dir.isDirectory() && dir.getAbsolutePath().endsWith("/WEB-INF/classes")) {
            final File pXmlStd = new File(dir.getParentFile(), "persistence.xml");
            if (pXmlStd.exists()) {
                return  pXmlStd.getAbsolutePath();
            }

            final File pXml = new File(dir, META_INF_PERSISTENCE_XML);
            if (pXml.exists()) {
                return pXml.getAbsolutePath();
            }
        }
        return null;
    }

    private String[] toFilePaths(final List<String> urls) {
        final List<String> files = new ArrayList<String>();
        for (String url : urls) {
            final File dir = new File(url);
            if (!dir.isDirectory()) {
                continue;
            }

            for (File f : Files.collect(dir, new ClassFilter())) {
                files.add(f.getAbsolutePath());
            }
        }
        return files.toArray(new String[files.size()]);
    }

    private static class ClassFilter implements FileFilter {
        private static final String DEFAULT_INCLUDE = "\\*";
        private static final String DEFAULT_EXCLUDE = "";
        private static final Pattern INCLUDE_PATTERN = Pattern.compile(SystemInstance.get().getOptions().get(OPENEJB_JAR_ENHANCEMENT_INCLUDE, DEFAULT_INCLUDE));
        private static final Pattern EXCLUDE_PATTERN = Pattern.compile(SystemInstance.get().getOptions().get(OPENEJB_JAR_ENHANCEMENT_EXCLUDE, DEFAULT_EXCLUDE));

        @Override
        public boolean accept(final File file) {
            boolean isClass = file.getName().endsWith(CLASS_EXT);
            if (DEFAULT_EXCLUDE.equals(EXCLUDE_PATTERN) && DEFAULT_INCLUDE.equals(INCLUDE_PATTERN)) {
                return isClass;
            }

            final String path = file.getAbsolutePath();
            return isClass && INCLUDE_PATTERN.matcher(path).matches() && !EXCLUDE_PATTERN.matcher(path).matches();
        }
    }

    private static class JarFileParser extends DefaultHandler {
        private final List<String> paths = new ArrayList<String>();
        private boolean getIt = false;

        @Override
        public void startElement(final String uri, final String localName, final String qName, final Attributes att) throws SAXException {
            if (!localName.endsWith("jar-file")) {
                return;
            }

            getIt = true;
        }

        @Override
        public void characters(final char ch[], final int start, final int length) throws SAXException {
            if (getIt) {
                paths.add(new StringBuilder().append(ch, start, length).toString());
            }
        }

        @Override
        public void endElement(final String uri, final String localName, final String qName) throws SAXException {
            getIt = false;
        }

        public List<String> getPaths() {
            return paths;
        }
    }

    private static class AlreadyEnhancedFilter implements Filter {
        public static final String SUFFIX = JAR_ENHANCEMENT_SUFFIX + ".jar";

        @Override
        public boolean accept(final String s) {
            return !s.endsWith(SUFFIX);
        }
    }
}