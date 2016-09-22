/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ekstep.ep.samza.task;

import com.library.checksum.system.ChecksumGenerator;
import com.library.checksum.system.KeysToAccept;
import org.apache.samza.config.Config;
import org.apache.samza.metrics.Counter;
import org.apache.samza.storage.kv.KeyValueStore;
import org.apache.samza.system.IncomingMessageEnvelope;
import org.apache.samza.system.OutgoingMessageEnvelope;
import org.apache.samza.system.SystemStream;
import org.apache.samza.task.*;
import org.ekstep.ep.samza.rule.*;
import org.ekstep.ep.samza.api.GoogleGeoLocationAPI;
import org.ekstep.ep.samza.logger.Logger;
import org.ekstep.ep.samza.service.DeviceService;
import org.ekstep.ep.samza.service.GoogleReverseSearchService;
import org.ekstep.ep.samza.service.LocationService;
import org.ekstep.ep.samza.system.*;

import java.util.*;

public class ReverseSearchStreamTask implements StreamTask, InitableTask, WindowableTask {

    static Logger LOGGER = new Logger(ReverseSearchStreamTask.class);
    private KeyValueStore<String, Object> deviceStore;
    private String successTopic;
    private String failedTopic;
    private String bypass;
    private ChecksumGenerator checksumGenerator;
    private LocationService locationService;
    private double reverseSearchCacheAreaSizeInMeters;
    private Counter messageCount;
    private DeviceService deviceService;
    private List<Rule> locationRules;

    @Override
    public void init(Config config, TaskContext context) {
        String apiKey = config.get("google.api.key", "");

        successTopic = config.get("output.success.topic.name", "events_with_location");
        failedTopic = config.get("output.failed.topic.name", "events_failed_location");
        reverseSearchCacheAreaSizeInMeters = Double.parseDouble(config.get("reverse.search.cache.area.size.in.meters",
                "200"));

        bypass = config.get("bypass", "true");

        KeyValueStore<String, Object> reverseSearchStore = (KeyValueStore<String, Object>) context.getStore("reverse-search");
        this.deviceStore = (KeyValueStore<String, Object>) context.getStore("device");
        GoogleReverseSearchService googleReverseSearch = new GoogleReverseSearchService(new GoogleGeoLocationAPI(apiKey));

        String[] keys_to_accept = {"uid", "ts", "gdata", "edata"};
        checksumGenerator = new ChecksumGenerator(new KeysToAccept(keys_to_accept));

        locationRules = Arrays.asList(new LocationPresent(), new LocationEmpty(), new LocationAbsent());

        locationService = new LocationService(reverseSearchStore,
                googleReverseSearch,
                reverseSearchCacheAreaSizeInMeters
                );
        deviceService = new DeviceService(deviceStore);
        messageCount = context
                .getMetricsRegistry()
                .newCounter(getClass().getName(), "message-count");
    }

    public ReverseSearchStreamTask() {
    }

    //For testing only
    ReverseSearchStreamTask(KeyValueStore<String, Object> deviceStore,
                            String bypass, LocationService locationService, DeviceService deviceService, List<Rule> locationRules) {
        this.deviceStore = deviceStore;
        this.bypass = bypass;
        String[] keys_to_accept = {"uid", "ts", "cid", "gdata", "edata"};
        checksumGenerator = new ChecksumGenerator(new KeysToAccept(keys_to_accept));
        this.locationService = locationService;
        this.deviceService =  deviceService;
        this.locationRules = locationRules;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void process(IncomingMessageEnvelope envelope, MessageCollector collector, TaskCoordinator coordinator) {
        Map<String, Object> jsonObject = null;
        try {
            jsonObject = (Map<String, Object>) envelope.getMessage();
            processEvent(new Event(jsonObject), collector);
            messageCount.inc();
        } catch (Exception e) {
            LOGGER.error(null, "PROCESSING FAILED: " + jsonObject, e);
        }
    }

    public void processEvent(Event event, MessageCollector collector) {
        event.setTimestamp();
        Location location = null;

        if (bypass.equals("true")) {
            LOGGER.info(event.id(), "BYPASSING: {}", event);
        } else {
            try {
                String did = event.getDid();
                for (Rule rule : locationRules) {
                    if(rule.isApplicableTo(event)){
                        rule.apply(event, locationService, deviceService);
                    }
                }
                location = deviceService.getLocation(did,event.id());
            } catch (Exception e) {
                LOGGER.error(null, "REVERSE SEARCH FAILED: " + event, e);
            }
        }

        try {
            if (location != null) {
                event.AddLocation(location);
                event.setFlag("ldata_obtained", true);
            } else {
                event.setFlag("ldata_obtained", false);
                collector.send(new OutgoingMessageEnvelope(new SystemStream("kafka", failedTopic), event.getMap()));
            }

            if (event.getMid() == null) {
                checksumGenerator.stampChecksum(event);
            } else {
                Map<String, Object> metadata = new HashMap<String, Object>();
                metadata.put("checksum", event.getMid());
                event.setMetadata(metadata);
            }
            event.setFlag("ldata_processed", true);
            collector.send(new OutgoingMessageEnvelope(new SystemStream("kafka", successTopic), event.getMap()));
        } catch (Exception e) {
            LOGGER.error(null, "ERROR WHEN ROUTING EVENT: {}" + event, e);
        }
    }

    @Override
    public void window(MessageCollector collector, TaskCoordinator coordinator) throws Exception {
        messageCount.clear();
    }
}
