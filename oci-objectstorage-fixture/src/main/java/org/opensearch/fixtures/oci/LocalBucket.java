package org.opensearch.fixtures.oci;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.Value;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.io.IOUtils;
import org.opensearch.repositories.oci.sdk.com.oracle.bmc.model.BmcException;

@Value
@Log4j2
public class LocalBucket {
    Map<String, OSObject> prefixToObjectMap = new ConcurrentHashMap<>(101);
    String name;

    public void putObject(String objectName, InputStream inputStream) throws IOException {
        log.info("objectName : " + objectName);
        log.info("inputStream : " + inputStream);
        log.info(name);
        log.info(inputStream);
        log.info(inputStream.available());

        final byte[] objectData = IOUtils.toByteArray(inputStream);
        final OSObject osObject = new OSObject(objectName, objectData);

        prefixToObjectMap.put(objectName, osObject);
    }

    public OSObject getObject(String objectName) {
        return prefixToObjectMap.get(objectName);
    }

    public OSObject deleteObject(String objectName) {
        if (!prefixToObjectMap.containsKey(objectName)) {
            throw new BmcException(
                    HttpURLConnection.HTTP_NOT_FOUND,
                    "object not found",
                    "object not found",
                    "object not found");
        }

        return prefixToObjectMap.remove(objectName);
    }

    public List<OSObject> getObjectByPrefix(String prefix) {
        return prefixToObjectMap.values().stream()
                .sorted(Comparator.comparing(OSObject::getPrefix))
                .filter(object -> object.getPrefix().contains(prefix))
                .collect(Collectors.toList());
    }
}
