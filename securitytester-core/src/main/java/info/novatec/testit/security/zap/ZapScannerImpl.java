package info.novatec.testit.security.zap;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zaproxy.clientapi.core.Alert;
import org.zaproxy.clientapi.core.ApiResponse;
import org.zaproxy.clientapi.core.ClientApi;
import org.zaproxy.clientapi.core.ClientApiException;
import org.zaproxy.clientapi.core.ApiResponseElement;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


/**
 * Interface to the Zap Scanner API.
 */
@SuppressWarnings("ALL")
public class ZapScannerImpl implements ZapScanner {

    private static final Logger LOG = LoggerFactory.getLogger(ZapScannerImpl.class);
    private static final String SESSION_NAME = "securitytester";
    private static final String CONTEXT_NAME = "securitytester";

    private ClientApi clientApi;
    private boolean withSpider;

    public ZapScannerImpl(String apiKey, String zapHost, String zapPort, boolean withSpider) {
        this.withSpider = withSpider;
        this.clientApi = new ClientApi(zapHost, Integer.parseInt(zapPort), apiKey);
        try {
            clientApi.core.newSession(null, Boolean.TRUE.toString());
            clientApi.context.newContext(CONTEXT_NAME);
            clientApi.context.setContextInScope(CONTEXT_NAME, Boolean.TRUE.toString());
        } catch (ClientApiException e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    @Override
    public List<Alert> completeScan(String baseUrl, boolean inScopeOnly, String scanPolicyName) {

        try {
            this.clientApi.context.includeInContext(CONTEXT_NAME, baseUrl + ".*");
        } catch (ClientApiException e) {
            throw new UndeclaredThrowableException(e);
        }

        List<Alert> alerts = new ArrayList<>();

        LOG.info("Started complete scan");
        try {

            if (withSpider) {
                spider(baseUrl);
                Thread.sleep(1500);//Let Spider some Time!
            } else {
                LOG.info("Spider is deactivated");
            }
            alerts.addAll(allActiveScan(baseUrl, inScopeOnly, scanPolicyName));

        } catch (InterruptedException e) {
            LOG.error("complete scan failed: " + e.getMessage());
        }
        LOG.info("Finished complete scan");

        return alerts;
    }

    @Override
    public List<Alert> activeScan(String baseUrl, boolean inScopeOnly, String scanPolicyName) {
        List<Alert> alerts = new ArrayList<>();

        try {
            LOG.info("Started active scan");

            //Active Scan needs an access Point! When it doesn't, it has to access first!
            if (!baseUrl.equals(clientApi.core.urls().getName())) {
                clientApi.core.accessUrl(baseUrl, null);
                clientApi.core.accessUrl(baseUrl, null);
            }

            clientApi.ascan.scan(baseUrl, "true",
                    Boolean.toString(inScopeOnly), scanPolicyName, null, null);

            int scanProgress = 0;
            int prevScanProgress;
            do {
                prevScanProgress = scanProgress;
                Thread.sleep(100);
                scanProgress = Integer.parseInt(((ApiResponseElement) clientApi.ascan.status(null)).getValue());
                if (LOG.isDebugEnabled() && prevScanProgress != scanProgress) {
                    LOG.debug("Scan progress : " + scanProgress + " %");
                }
            } while (scanProgress < 100);

            LOG.info("Finished active scan");

            alerts = showAlerts(baseUrl);
        } catch (ClientApiException | InterruptedException e) {
            LOG.error("Active Scan failed: " + e.getMessage());
        }
        return alerts;
    }

    @Override
    public List<Alert> allActiveScan(String baseUrl, boolean inScopeOnly, String scanPolicyName) {
        List<Alert> alerts = new ArrayList<>();
        try {
            clientApi.ascan.enableAllScanners(scanPolicyName);
            alerts = activeScan(baseUrl, inScopeOnly, scanPolicyName);
        } catch (ClientApiException e) {
            LOG.error("All Active Scan failed: " + e.getMessage());
        }
        return alerts;
    }

    @Override
    public void enablePassiveScan() {
        try {
            clientApi.pscan.setEnabled("true");
            clientApi.pscan.enableAllScanners();

        } catch (ClientApiException e) {
            LOG.error("Enable Passive Scan failed: " + e.getMessage());
        }
    }

    @Override
    public void disablePassiveScan() {
        try {
            clientApi.pscan.disableAllScanners();
        } catch (ClientApiException e) {
            LOG.error("Disable Passive Scan failed: " + e.getMessage());
        }
    }

    @Override
    public void spider(String baseUrl) {
        LOG.info("Started Spider");

        try {
            ApiResponse resp = clientApi.spider.scan(
                    baseUrl, null, null, null, null);
            String scanId = ((ApiResponseElement) resp).getValue();

            int progress;
            while (true) {
                Thread.sleep(50);
                progress = Integer.parseInt(((ApiResponseElement) clientApi.spider.status(scanId)).getValue());
                LOG.info("Spider progress : " + progress + "%");
                if (progress >= 100) {
                    break;
                }
            }
            LOG.info("Spider complete");

        } catch (ClientApiException | InterruptedException e) {
            LOG.error("Spider failed: " + e.getMessage());
        }
    }


    private List<Alert> showAlerts(String baseUrl) throws ClientApiException {
        List<Alert> alerts;
        waitForAlerts(baseUrl);

        alerts = getAlerts(baseUrl);

        if (LOG.isInfoEnabled()) {
            LOG.info("Found {} Alerts:", alerts.size());

            alerts.sort(Comparator.comparing(Alert::getRisk));

            for (Alert alert : alerts) {
                LOG.info(String.format("Risk %s: %s, confidence=%s, %s, details: %s", alert.getRisk(), alert.getAlert(),
                        alert.getConfidence().name(), alert.getDescription(),
                        StringUtils.isNotBlank(alert.getEvidence()) ? alert.getEvidence()
                                : StringUtils.isNotBlank(alert.getAttack()) ? alert.getAttack() : alert.getParam()));
            }
        }

        return alerts;
    }

    @SuppressWarnings("PMD.UselessParentheses")
    private void waitForAlerts(String baseUrl) throws ClientApiException {
        int numChecks = 0;

        int previousNum = 0;
        int currentNum = getNumberOfAlerts(baseUrl);

        while ((currentNum == 0 || (currentNum > 0 && currentNum > previousNum)) && ++numChecks < 10) {
            previousNum = currentNum;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                LOG.error("Wait for Alerts failed: " + e.getMessage());
            }
            currentNum = getNumberOfAlerts(baseUrl);
        }
    }

    private List<Alert> getAlerts(String baseUrl) throws ClientApiException {
        return clientApi.getAlerts(baseUrl, -1, -1);
    }

    private int getNumberOfAlerts(String baseUrl) throws ClientApiException {
        ApiResponse apiResponse = clientApi.core.numberOfAlerts(baseUrl);
        if (apiResponse != null && apiResponse instanceof ApiResponseElement) {
            ApiResponseElement element = (ApiResponseElement) apiResponse;
            return Integer.parseInt(element.getValue());
        } else {
            return 0;
        }
    }

}