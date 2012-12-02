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

import com.zimbra.common.localconfig.LC;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.Domain;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.ldap.LdapProvisioning;
import com.zimbra.cs.util.AccountUtil;
import com.zimbra.cs.util.Zimbra;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.SearchResult;

public class ADProvisioning extends LdapProvisioning {

    private static ADProvisioning SINGLETON = null;

    private static synchronized void ensureSingleton(ADProvisioning prov) {
        if (SINGLETON != null) {
            // pass an exception to have the stack logged
            Zimbra.halt("Only one instance of ADProvisioning can be created",
                    ServiceException.FAILURE("failed to instantiate ADProvisioning", null));
        }
        SINGLETON = prov;
    }

    public ADProvisioning() {
        super();
    }    

    @Override
    public Account get(AccountBy keyType, String key) throws ServiceException {
        return this.get(keyType, key, false);
    }    

    @Override
    public Account get(AccountBy keyType, String key, boolean loadFromMaster) throws ServiceException {
        Account acct = super.get(keyType, key, loadFromMaster);
        if (isEnabled() && (acct == null) && (keyType == AccountBy.name)) {
            acct = autoProvision(key);
        }
        return acct;
    }
            
    boolean isEnabled() {
        return LC.get("adprovision_sync_mode").equals("lazy");
    }

    Account autoProvision(String key) throws ServiceException {
        Account acct = null;
        Provisioning prov = Provisioning.getInstance();

        String defaultDomainName = LC.get("adprovision_domain_name");
        if (defaultDomainName.equals("")) {
            defaultDomainName = Provisioning.getInstance().getConfig().getDefaultDomainName();
        }
        
        Domain defaultDomain = prov.getDomainByName(defaultDomainName);

        ADConnection adc;
        try {
            adc = new ADConnection(defaultDomain);
        } catch (NamingException ex) {
            adc = null;
            ZimbraLog.account.info(ex);
        }

        if (adc == null) {
            return null;
        }
        
        ZimbraLog.account.info("[ADProvisioning] Autoprovisioning user "+key);
        try {
            NamingEnumeration entries = adc.fetchUser(key);
            if (entries.hasMore()) {
                // user found in AD
                SearchResult entry = (SearchResult)entries.nextElement();
                
                acct = ADUser.createAccount(entry, defaultDomainName);
                
                if (acct != null) {
                    AccountUtil.addAccountToLogContext(prov, acct.getId(), ZimbraLog.C_NAME, ZimbraLog.C_ID, null);
                }
            } else {
                ZimbraLog.account.info("[ADProvisioning] User "+key+" not found in AD");
            }
        } catch (NamingException ex) {
            ZimbraLog.account.info("[ADProvisioning] Unable to search user in AD: %s", ex);
        }
        
        return acct;
    }
}
