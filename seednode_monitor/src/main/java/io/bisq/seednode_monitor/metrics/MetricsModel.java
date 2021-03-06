/*
 * This file is part of bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bisq.seednode_monitor.metrics;

import io.bisq.common.util.MathUtils;
import io.bisq.network.p2p.NodeAddress;
import io.bisq.network.p2p.seed.SeedNodesRepository;
import io.bisq.seednode_monitor.MonitorOptionKeys;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.gpedro.integrations.slack.SlackApi;
import net.gpedro.integrations.slack.SlackMessage;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class MetricsModel {
    private SlackApi slackApi;
    @Getter
    private String resultAsString;
    @Getter
    private String resultAsHtml;
    private SeedNodesRepository seedNodesRepository;
    @Setter
    private long lastCheckTs;
    private int totalErrors = 0;
    private HashMap<NodeAddress, Metrics> map = new HashMap<>();

    @Inject
    public MetricsModel(SeedNodesRepository seedNodesRepository,
                        @Named(MonitorOptionKeys.SLACK_URL_SEED_CHANNEL) String slackUrlSeedChannel) {
        this.seedNodesRepository = seedNodesRepository;
        if (!slackUrlSeedChannel.isEmpty())
            slackApi = new SlackApi(slackUrlSeedChannel);
    }

    public void addToMap(NodeAddress nodeAddress, Metrics metrics) {
        map.put(nodeAddress, metrics);
    }

    public Metrics getMetrics(NodeAddress nodeAddress) {
        return map.get(nodeAddress);
    }

    public void updateReport() {
        Map<String, Double> accumulatedValues = new HashMap<>();
        final double[] items = {0};
        List<Map.Entry<NodeAddress, Metrics>> entryList = map.entrySet().stream()
                .sorted(Comparator.comparing(entrySet -> seedNodesRepository.getOperator(entrySet.getKey())))
                .collect(Collectors.toList());

        totalErrors = 0;
        entryList.stream().forEach(e -> {
            totalErrors += e.getValue().errorMessages.stream().filter(s -> !s.isEmpty()).count();
            final List<Map<String, Integer>> receivedObjectsList = e.getValue().getReceivedObjectsList();
            if (!receivedObjectsList.isEmpty()) {
                items[0] += 1;
                Map<String, Integer> last = receivedObjectsList.get(receivedObjectsList.size() - 1);
                last.entrySet().stream().forEach(e2 -> {
                    int accuValue = e2.getValue();
                    if (accumulatedValues.containsKey(e2.getKey()))
                        accuValue += accumulatedValues.get(e2.getKey());

                    accumulatedValues.put(e2.getKey(), (double) accuValue);
                });
            }
        });

        Map<String, Double> averageValues = new HashMap<>();
        accumulatedValues.entrySet().stream().forEach(e -> {
            averageValues.put(e.getKey(), e.getValue() / items[0]);
        });

        Calendar calendar = new GregorianCalendar();
        calendar.setTimeZone(TimeZone.getTimeZone("CET"));
        calendar.setTimeInMillis(lastCheckTs);
        final String time = calendar.getTime().toString();

        StringBuilder html = new StringBuilder();
        html.append("<html>" +
                "<head>" +
                "<style>table, th, td {border: 1px solid black;}</style>" +
                "</head>" +
                "<body>" +
                "<h1>")
                .append("Seed nodes in error: <b>" + totalErrors + "</b><br/>" +
                        "Last check started at: " + time + "<br/>" +
                        "<table style=\"width:100%\">" +
                        "<tr>" +
                        "<th align=\"left\">Operator</th>" +
                        "<th align=\"left\">Node address</th>" +
                        "<th align=\"left\">Num requests</th>" +
                        "<th align=\"left\">Num errors</th>" +
                        "<th align=\"left\">Last error message</th>" +
                        "<th align=\"left\">RRT average</th>" +
                        "<th align=\"left\">Last data</th>" +
                        "<th align=\"left\">Data deviation last request</th>" +
                        "</tr>");

        StringBuilder sb = new StringBuilder();
        sb.append("Seed nodes in error:" + totalErrors);
        sb.append("\nLast check started at: " + time + "\n");

        entryList.stream().forEach(e -> {
            final List<Long> allDurations = e.getValue().getRequestDurations();
            final String allDurationsString = allDurations.stream().map(Object::toString).collect(Collectors.joining("<br/>"));
            final OptionalDouble averageOptional = allDurations.stream().mapToLong(value -> value).average();
            double durationAverage = 0;
            if (averageOptional.isPresent())
                durationAverage = averageOptional.getAsDouble() / 1000;
            final NodeAddress nodeAddress = e.getKey();
            final String operator = seedNodesRepository.getOperator(nodeAddress);
            final List<String> errorMessages = e.getValue().getErrorMessages();
            final int numErrors = (int) errorMessages.stream().filter(s -> !s.isEmpty()).count();
            int numRequests = allDurations.size();
            String lastErrorMsg = "";
            int lastIndexOfError = -1;
            for (int i = 0; i < errorMessages.size(); i++) {
                final String msg = errorMessages.get(i);
                if (!msg.isEmpty()) {
                    lastIndexOfError = i;
                    lastErrorMsg = "Error at request " + lastIndexOfError + ":" + msg;
                }
            }
            //  String lastErrorMsg = numErrors > 0 ? errorMessages.get(errorMessages.size() - 1) : "";
            final List<Map<String, Integer>> allReceivedData = e.getValue().getReceivedObjectsList();
            Map<String, Integer> lastReceivedData = !allReceivedData.isEmpty() ? allReceivedData.get(allReceivedData.size() - 1) : new HashMap<>();
            final String lastReceivedDataString = lastReceivedData.entrySet().stream().map(Object::toString).collect(Collectors.joining("<br/>"));
            final String allReceivedDataString = allReceivedData.stream().map(Object::toString).collect(Collectors.joining("<br/>"));

            sb.append("\nOperator: ").append(operator)
                    .append("\nNode address: ").append(nodeAddress)
                    .append("\nNum requests: ").append(numRequests)
                    .append("\nNum errors: ").append(numErrors)
                    .append("\nLast error message: ").append(lastErrorMsg)
                    .append("\nRRT average: ").append(durationAverage)
                    .append("\nLast data: ").append(lastReceivedDataString);

            String colorNumErrors = lastIndexOfError == numErrors ? "black" : "red";
            String colorDurationAverage = durationAverage < 30 ? "black" : "red";
            html.append("<tr>")
                    .append("<td>").append("<font color=\"" + colorNumErrors + "\">" + operator + "</font> ").append("</td>")
                    .append("<td>").append("<font color=\"" + colorNumErrors + "\">" + nodeAddress + "</font> ").append("</td>")
                    .append("<td>").append("<font color=\"" + colorNumErrors + "\">" + numRequests + "</font> ").append("</td>")
                    .append("<td>").append("<font color=\"" + colorNumErrors + "\">" + numErrors + "</font> ").append("</td>")
                    .append("<td>").append("<font color=\"" + colorNumErrors + "\">" + lastErrorMsg + "</font> ").append("</td>")
                    .append("<td>").append("<font color=\"" + colorDurationAverage + "\">" + durationAverage + "</font> ").append("</td>")
                    .append("<td>").append(lastReceivedDataString).append("</td><td>");

            if (!allReceivedData.isEmpty()) {
                sb.append("\nData deviation last request:\n");
                lastReceivedData.entrySet().stream().forEach(e2 -> {
                    final String dataItem = e2.getKey();
                    double deviation = MathUtils.roundDouble((double) e2.getValue() / averageValues.get(dataItem) * 100, 2);
                    String str = dataItem + ": " + deviation + "%";
                    sb.append(str).append("\n");
                    String color;
                    final double devAbs = Math.abs(deviation - 100);
                    if (devAbs < 5)
                        color = "black";
                    else if (devAbs < 10)
                        color = "blue";
                    else
                        color = "red";

                    html.append("<font color=\"" + color + "\">" + str + "</font> ").append("<br/>");

                    if (devAbs >= 20) {
                        if (slackApi != null)
                            slackApi.call(new SlackMessage("Warning: " + nodeAddress.getFullAddress(),
                                    "<" + seedNodesRepository.getSlackUser(nodeAddress) + ">" + " Your seed node delivers diverging results for " + dataItem + ". " +
                                            "Please check the monitoring status page at http://seedmonitor.0-2-1.net:8080/"));
                    }
                });
                sb.append("Duration all requests: ").append(allDurationsString)
                        .append("\nAll data: ").append(allReceivedDataString).append("\n");

                html.append("</td></tr>");
            }
        });
        html.append("</table></body></html>");
        resultAsString = sb.toString();
        resultAsHtml = html.toString();
    }

    public void log() {
        log.info("\n\n#################################################################\n" +
                resultAsString +
                "#################################################################\n\n");
    }
}
