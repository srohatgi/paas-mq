package com.intocloudtech.paas.mq;

import com.amazonaws.services.ecs.model.Cluster;
import java.util.List;

/**
 * Created by sumeet on 2/3/17.
 */
public interface Recipe {
    Cluster buildCluster();

    List<String> bootZooKeeper(Cluster cluster);

    void bootKafka(Cluster cluster, List<String> zkIps);

    default void execute() {
        Cluster cluster = buildCluster();
        List<String> zkIps = bootZooKeeper(cluster);
        bootKafka(cluster, zkIps);
    }
}
