/***********************************************************************
 * This file is part of LaS-VPE Platform.
 *
 * LaS-VPE Platform is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LaS-VPE Platform is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LaS-VPE Platform.  If not, see <http://www.gnu.org/licenses/>.
 ************************************************************************/

package org.cripac.isee.vpe.alg;

import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.log4j.Level;
import org.cripac.isee.pedestrian.attr.Attributes;
import org.cripac.isee.pedestrian.attr.DeepMAR;
import org.cripac.isee.pedestrian.attr.ExternPedestrianAttrRecognizer;
import org.cripac.isee.pedestrian.attr.PedestrianAttrRecognizer;
import org.cripac.isee.pedestrian.tracking.Tracklet;
import org.cripac.isee.vpe.common.DataTypes;
import org.cripac.isee.vpe.common.Topic;
import org.cripac.isee.vpe.ctrl.TaskData;
import org.cripac.isee.vpe.ctrl.TopicManager;
import org.cripac.isee.vpe.debug.FakePedestrianTracker;
import org.cripac.isee.vpe.util.logging.ConsoleLogger;
import org.junit.Before;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;

import static org.bytedeco.javacpp.opencv_imgcodecs.imread;
import static org.cripac.isee.pedestrian.attr.DeepMARTest.img2Tracklet;
import static org.cripac.isee.vpe.util.SerializationHelper.deserialize;
import static org.cripac.isee.vpe.util.SerializationHelper.serialize;
import static org.cripac.isee.vpe.util.kafka.KafkaHelper.sendWithLog;

/**
 * This is a JUnit test for the DataManagingApp.
 * Different from usual JUnit tests, this test does not initiate a DataManagingApp.
 * The application should be run on YARN in advance.
 * This test only sends fake data messages to and receives results
 * from the already running application through Kafka.
 * <p>
 * Created by ken.yu on 16-10-31.
 */
public class PedestrianAttrRecogAppTest {

    public static final Topic TEST_PED_ATTR_RECV_TOPIC
            = new Topic("test-pedestrian-attr-recv", DataTypes.ATTR, null);

    private KafkaProducer<String, byte[]> producer;
    private KafkaConsumer<String, byte[]> consumer;
    private ConsoleLogger logger;
    public InetAddress externAttrRecogServerAddr;
    public int externAttrRecogServerPort = 0;
    private static boolean toTestApp = true;

    public static void main(String[] args) {
        PedestrianAttrRecogAppTest test = new PedestrianAttrRecogAppTest();
        try {
            test.init(args);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        try {
            test.testDeepMAR();
            test.testExternAttrReognizer();
            if (toTestApp) {
                test.testAttrRecogApp();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Before
    public void init() throws Exception {
        init(new String[0]);
    }

    public void init(String[] args) throws ParserConfigurationException, UnknownHostException, SAXException, URISyntaxException {
        logger = new ConsoleLogger(Level.DEBUG);

        PedestrianAttrRecogApp.AppPropertyCenter propCenter =
                new PedestrianAttrRecogApp.AppPropertyCenter(args);

        externAttrRecogServerAddr = propCenter.externAttrRecogServerAddr;
        externAttrRecogServerPort = propCenter.externAttrRecogServerPort;

        try {
            TopicManager.checkTopics(propCenter);

            Properties producerProp = propCenter.generateKafkaProducerProp(false);
            producer = new KafkaProducer<>(producerProp);

            Properties consumerProp = propCenter.generateKafkaConsumerProp(UUID.randomUUID().toString(), false);
            consumer = new KafkaConsumer<>(consumerProp);
            consumer.subscribe(Arrays.asList(TEST_PED_ATTR_RECV_TOPIC.NAME));
        } catch (Exception e) {
            logger.error("When checking topics", e);
            logger.info("App test is disabled.");
            toTestApp = false;
        }
    }

    //    @Test
    public void testExternAttrReognizer() throws IOException {
        logger.info("Testing extern attr recognizer.");
        PedestrianAttrRecognizer recognizer =
                new ExternPedestrianAttrRecognizer(externAttrRecogServerAddr,
                        externAttrRecogServerPort, logger);
        Tracklet tracklet = new FakePedestrianTracker().track(null)[0];
        logger.info("Tracklet length: " + tracklet.locationSequence.length);
        for (Tracklet.BoundingBox boundingBox : tracklet.locationSequence) {
            logger.info("\tbbox: " + boundingBox.x + " " + boundingBox.y
                    + " " + boundingBox.width + " " + boundingBox.height);
        }
        logger.info(recognizer.recognize(tracklet));
    }

    public void testDeepMAR() throws IOException {
        PedestrianAttrRecognizer recognizer = new DeepMAR(-1, logger);

        final String testImage = "src/test/resources/" +
                "CAM01_2014-02-15_20140215161032-20140215162620_tarid0_frame218_line1.png";
        Attributes attributes = recognizer.recognize(img2Tracklet(imread(testImage)));
        logger.info(attributes);
    }

    //    @Test
    public void testAttrRecogApp() throws Exception {
        logger.info("Testing attr recogn app.");
        TaskData.ExecutionPlan plan = new TaskData.ExecutionPlan();
        TaskData.ExecutionPlan.Node recogNode = plan.addNode(PedestrianAttrRecogApp.RecogStream.INFO);
        plan.letNodeOutputTo(recogNode, TEST_PED_ATTR_RECV_TOPIC);

        // Send request (fake tracklet).
        TaskData trackletData = new TaskData(recogNode, plan,
                new FakePedestrianTracker().track(null)[0]);
        assert trackletData.predecessorRes != null && trackletData.predecessorRes instanceof Tracklet;
        sendWithLog(PedestrianAttrRecogApp.RecogStream.TRACKLET_TOPIC,
                UUID.randomUUID().toString(),
                serialize(trackletData),
                producer,
                logger);

        logger.info("Waiting for response...");
        // Receive result (attributes).
        ConsumerRecords<String, byte[]> records;
        while (true) {
            records = consumer.poll(0);
            if (records.isEmpty()) {
                continue;
            }

            logger.info("Response received!");
            records.forEach(rec -> {
                TaskData taskData;
                try {
                    taskData = deserialize(rec.value());
                } catch (Exception e) {
                    logger.error("During TaskData deserialization", e);
                    return;
                }
                logger.info("<" + rec.topic() + ">\t" + rec.key() + "\t-\t" + taskData.predecessorRes);
            });
        }
    }
}
