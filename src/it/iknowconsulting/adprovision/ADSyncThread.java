/*
   Copyright 2012 Antonio Messina (a.messina@iknowconsulting.it)

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. 
*/

package it.iknowconsulting.adprovision;

import com.zimbra.common.localconfig.ConfigException;
import com.zimbra.common.localconfig.LC;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.EmailUtil;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.Domain;
import com.zimbra.cs.account.Provisioning;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchResult;
import org.dom4j.DocumentException;

public class ADSyncThread extends Thread {

    private static volatile ADSyncThread sADSyncThread = null;
    private static Object THREAD_CONTROL_LOCK = new Object();
    private boolean mShutdownRequested = false;
    
    private ADSyncThread() {
        setName("ADProvision");
    }

    // Starts up the active directory sync thread.
    public synchronized static void startup() {
        synchronized (THREAD_CONTROL_LOCK) {
            if (isRunning()) {
                ZimbraLog.account.info("[ADSyncThread] Cannot start a second thread while another one is running.");
                return;
            }

            // init values
            setInitialSleep();
            ZimbraLog.account.info("[ADSyncThread] Starting thread with sleep interval of %d min", getSleepInterval());

            // Start thread
            sADSyncThread = new ADSyncThread();
            sADSyncThread.start();
        }
    }

    public synchronized static boolean isRunning() {
        synchronized (THREAD_CONTROL_LOCK) {
            if (sADSyncThread != null) {
                return true;
            } else {
                return false;
            }
        }
    }

    public synchronized static void shutdown() {
        synchronized (THREAD_CONTROL_LOCK) {
            if (sADSyncThread != null) {
                sADSyncThread.requestShutdown();
                sADSyncThread.interrupt();
                sADSyncThread = null;
            } else {
                ZimbraLog.account.info("[ADSyncThread] shutdown() called, but thread is not running.");
            }
        }
    }
    
    @Override
    public void run() {
        // Sleep before doing work, to give the server time to warm up. Also limits the amount
        // of random effect when determining the next mailbox id.
        int sleepTime;
        sleepTime = getInitialSleep();
        ZimbraLog.account.info("[ADSyncThread] Sleeping for %d min before doing work.", sleepTime);
        try {
            Thread.sleep(sleepTime*60000);
        } catch (InterruptedException e) {
            ZimbraLog.account.info("[ADSyncThread] Shutting down");
            sADSyncThread = null;
            return;
        }
                
        Security.addProvider(new com.sun.net.ssl.internal.ssl.Provider());
        System.setProperty("javax.net.ssl.trustStore", "/opt/zimbra/java/jre/lib/security/cacerts");
        System.setProperty("javax.net.info", "all");

        Provisioning prov = Provisioning.getInstance();

        while (true) {
            if (mShutdownRequested) {
                ZimbraLog.account.info("[ADSyncThread] Shutting down");
                sADSyncThread = null;
                return;
            }
            
            try {
                LC.reload();
            } catch (ConfigException ex) {
                ZimbraLog.account.info("[ADSyncThread] Unable to reload local configuration: %s", ex);
                return;
            } catch (DocumentException ex) {
                ZimbraLog.account.info("[ADSyncThread] Unable to reload local configuration: %s", ex);
            }

            setSleepInterval();
            setDefaultDomainName();
            setSyncMode();

            if (isEagerModeEnabled) {
                Domain defaultDomain;
                try {
                    defaultDomain = prov.getDomainByName(getDefaultDomainName());
                } catch (ServiceException ex) {
                    ZimbraLog.account.info("[ADSyncThread] Default domain not found: %s", ex);
                    return;
                }

                ADConnection adc;
                try {
                    adc = new ADConnection(defaultDomain);
                } catch (NamingException ex) {
                    ZimbraLog.account.info("[ADSyncThread] Unable to connect to AD: %s", ex);
                    return;
                }

                doSyncFromAD(prov, adc);
            }

            sleep();
        }
    }

    private void doSyncFromAD(Provisioning prov, ADConnection adc) {
        ZimbraLog.account.info("[ADSyncThread] Starting AD eager mode autoprovisioning");

        NamingEnumeration entries = null;
        try {
            entries = adc.getUsers();
            if (!entries.hasMore()) {
                ZimbraLog.account.info("[ADSyncThread] No users in AD? Exiting...");
                return;
            }
        } catch (NamingException ex) {
            ZimbraLog.account.info("[ADSyncThread] Unable to fetch user list from AD: %", ex);
        }

        if (entries == null) {
            return;
        }

        int totalNewUsers = 0;

        List<String> domainList = new ArrayList<String>();

        try {
            while (entries.hasMore()) {
                if (mShutdownRequested) {
                    ZimbraLog.account.info("[ADSyncThread] Shutting down AD eager mode autoprovisioning");
                    sADSyncThread = null;
                    return;
                }

                SearchResult entry = (SearchResult)entries.nextElement();

                Attributes attributes = entry.getAttributes();

                String sAMAccountName = attributes.get("sAMAccountName").get(0).toString();
                String userPrincipalName = attributes.get("userPrincipalName").get(0).toString();

                if (userPrincipalName == null) {
                    continue;
                }

                String domainName = EmailUtil.getValidDomainPart(userPrincipalName);
                if (domainName == null) {
                    continue;
                }

                if (!domainList.contains(domainName)) {
                    //Check if this domain is in Zimbra
                    Domain domain = prov.getDomainByName(domainName);
                    if (domain == null) {
                        continue;
                    }
                    domainList.add(domainName);
                }

                Account acct = prov.getAccountByName(sAMAccountName);
                if (acct == null) {
                    acct = ADUser.createAccount(entry, getDefaultDomainName());
                    if (acct != null) {
                        totalNewUsers++;
                    }
                    if (totalNewUsers == sBatchSize) {
                        break;
                    }
                }
            }
        } catch (NamingException ex) {
            ZimbraLog.account.info("[ADSyncThread] %s", ex);
        } catch (ServiceException ex) {
            ZimbraLog.sync.info("[ADSyncThread] %s", ex);
        }

        ZimbraLog.account.info("[ADSyncThread] Created %d new users", totalNewUsers);
        ZimbraLog.account.info("[ADSyncThread] AD eager mode autoprovisioning stopped");        
    }

    private void sleep() {
        int interval = getSleepInterval();
        ZimbraLog.account.info("[ADSyncThread] Sleeping for %d minutes", interval);
        
        if (interval > 0) {
            try {
                Thread.sleep(interval*60000);
            } catch (InterruptedException e) {
                ZimbraLog.account.info("[ADSyncThread] Interrupted");
                mShutdownRequested = true;
            }
        } else {
            mShutdownRequested = true;
        }
    }
    
    private void requestShutdown() {
        mShutdownRequested = true;
    }
    
    private static int sInitialSleep = 2;
    private static int sSleepInterval = 30;
    private static String sDefaultDomainName = "example.com";
    private static boolean isEagerModeEnabled = false;
    private static int sBatchSize = 10;
    
    private static void setSyncMode() {
        String lcSyncMode = LC.get("adprovision_sync_mode");
        if (lcSyncMode.equals("eager")) {
            isEagerModeEnabled = true;
            String lcBatchSize = LC.get("adprovision_batch_size");
            try {
                sBatchSize = Integer.valueOf(lcBatchSize);
            } catch (NumberFormatException nfe) {
            }                    
            if (sBatchSize < 1) {
                isEagerModeEnabled = false;
            }
        } else {
            isEagerModeEnabled = false;
        }
    }
    
    private static void setDefaultDomainName() {
        String lcDefaultDomain = LC.get("adprovision_domain_name");
        if (!lcDefaultDomain.equals("")) {
            sDefaultDomainName = lcDefaultDomain;
        } else {
            try {
                // try to set automatically
                sDefaultDomainName = Provisioning.getInstance().getConfig().getDefaultDomainName();
            } catch (ServiceException ex) {
                ZimbraLog.account.info("[ADSyncThread] Can't set the default domain name. Default value <"+sDefaultDomainName+ "> will not work: %s", ex);
            }
        }
    }
    private static String getDefaultDomainName() {
        return sDefaultDomainName;
    }
    
    private static void setSleepInterval() {
        String lcSleepInterval = LC.get("adprovision_sleep_interval");
        try {
            sSleepInterval = Integer.valueOf(lcSleepInterval);
        } catch (NumberFormatException nfe) {
        }
    }
    private static int getSleepInterval() {
        return sSleepInterval;
    }

    private static void setInitialSleep() {
        String lcInitialSleep = LC.get("adprovision_initial_sleep");
        try {
            sInitialSleep = Integer.valueOf(lcInitialSleep);
        } catch (NumberFormatException nfe) {
        }
    }
    private static int getInitialSleep() {
        return sInitialSleep;
    }
}
