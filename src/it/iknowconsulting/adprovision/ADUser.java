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

import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.EmailUtil;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.Provisioning;
import java.util.HashMap;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchResult;

public class ADUser {

    public static Account createAccount(SearchResult entry, String defaultDomainName) throws ServiceException {
        Account acct = null;
        Provisioning prov = Provisioning.getInstance();

        try {
            Attributes attributes = entry.getAttributes();
            String givenName = attributes.get("givenName").get(0).toString();
            String name = attributes.get("name").get(0).toString();
            String sn = "";
            try {
                sn = attributes.get("sn").get(0).toString();
            } catch(NullPointerException npe) {
            }
            String userPrincipalName = attributes.get("userPrincipalName").get(0).toString();
            String mail = "";
            try {
                mail = attributes.get("mail").get(0).toString();
            } catch(NullPointerException npe) {
            }
            String sAMAccountName = attributes.get("sAMAccountName").get(0).toString();

            String parts[] = EmailUtil.getLocalPartAndDomain(userPrincipalName);
            if (parts == null) {
                return null;
            }

            // return if the user domain name is not our default domain name
            if (!defaultDomainName.equals(parts[1])) {
                ZimbraLog.account.info("[ADUser] User %s is not in our default domain", userPrincipalName);
                return null;
            }

            String mailAccount = parts[0];
            String mailparts[] = EmailUtil.getLocalPartAndDomain(mail);
            if (mailparts != null) {
                mailAccount = mailparts[0];
            }

            ZimbraLog.account.info("[ADUser] Creating user: %s '%s' <%s@%s>", givenName, name, sAMAccountName, defaultDomainName);
            HashMap attrs  = new HashMap();
            attrs.put(Provisioning.A_givenName, givenName);
            if (!sn.equals("")) {
                attrs.put(Provisioning.A_sn, sn);
            }
            attrs.put(Provisioning.A_cn, name);
            attrs.put(Provisioning.A_displayName, name);
            attrs.put(Provisioning.A_zimbraMailStatus, Provisioning.MAIL_STATUS_ENABLED);

            acct = prov.createAccount(userPrincipalName, "AUTOPROVISIONED", attrs);

            if (!mailAccount.equals(sAMAccountName)) {
                ZimbraLog.account.info("[ADUser] Creating alias <%s@%s> for user %s", mailAccount, defaultDomainName, sAMAccountName);
                prov.addAlias(acct, mail);
            }        
        } catch (NamingException ex) {
            ZimbraLog.account.info("[ADUser] Unable to fetch attributes from AD for the user %s", ex);
        }
        
        return acct;
    }
}
