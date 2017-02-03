package com.intocloudtech.paas.mq;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.ecs.AmazonECS;
import com.amazonaws.services.ecs.AmazonECSClientBuilder;
import com.amazonaws.services.ecs.model.CreateClusterRequest;
import com.amazonaws.services.ecs.model.CreateClusterResult;

import com.beust.jcommander.JCommander;

import com.beust.jcommander.Parameter;


/**
 * build a spec for mq-service cluster
 * for launching in aws-ecs
 */
public class MQSpec {
    @Parameter(names = { "--instances" }, description = "Instances of mq containers")
    int instances = 1;
    @Parameter(names = { "--storage" }, description = "Storage in GiB for each mq container")
    int gibStoragePerNode = 10;
    @Parameter(names = { "--cluster" }, description = "Cluster name")
    String clusterName = "j-sumeet";

    public static void main(String... args) {
        MQSpec spec = new MQSpec();
        new JCommander(spec, args);
        spec.run();
    }

    void run() {
        AmazonECS ecs = AmazonECSClientBuilder
                .standard()
                .withRegion(Regions.US_WEST_1).build();

        long clusterCount = ecs.describeClusters()
                .getClusters()
                .stream()
                .filter(c -> c.getClusterName().equals(clusterName))
                .count();

        if (clusterCount == 0) {
            CreateClusterRequest request = new CreateClusterRequest().withClusterName(clusterName);

            CreateClusterResult result = ecs.createCluster(request);
        }
    }
}
