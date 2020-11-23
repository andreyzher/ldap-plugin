/*
 * The MIT License
 *
 * Copyright (c) 2020 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.security;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import java.text.ParseException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Provides helpers for checking operational attributes of LDAP user accounts for validating an account is still in
 * good standing for indirect use.
 *
 * @see <a href="https://issues.jenkins.io/browse/JENKINS-55813">JENKINS-55813</a>
 */
final class UserAttributesHelper {

    // https://support.microsoft.com/en-us/help/305144/how-to-use-the-useraccountcontrol-flags-to-manipulate-user-account-pro
    // https://docs.microsoft.com/en-us/windows/win32/adschema/a-useraccountcontrol
    private static final String ATTR_USER_ACCOUNT_CONTROL = "userAccountControl";
    // https://docs.microsoft.com/en-us/windows/win32/adschema/a-accountexpires
    private static final String ATTR_ACCOUNT_EXPIRES = "accountExpires";
    // https://ldapwiki.com/wiki/Draft-behera-ldap-password-policy
    private static final String ATTR_LOGIN_DISABLED = "loginDisabled";
    private static final String ATTR_ORACLE_IS_ENABLED = "orclIsEnabled";
    private static final String ATTR_PWD_ACCOUNT_LOCKED_TIME = "pwdAccountLockedTime";
    private static final String ATTR_PWD_START_TIME = "pwdStartTime";
    private static final String ATTR_PWD_END_TIME = "pwdEndTime";
    private static final String ATTR_LOGIN_EXPIRATION_TIME = "loginExpirationTime";
    private static final String ATTR_PWD_LOCKOUT = "pwdLockout";
    // https://ldapwiki.com/wiki/Locked%20By%20Intruder
    private static final String ATTR_LOCKED_BY_INTRUDER = "lockedByIntruder";
    // for Windows Server 2003-based domain
    // https://docs.microsoft.com/en-us/windows/win32/adschema/a-msds-user-account-control-computed
    private static final String ATTR_USER_ACCOUNT_CONTROL_COMPUTED = "msDS-User-Account-Control-Computed";
    // for ADAM (Active Directory Application Mode), replace the ADS_UF_DISABLED
    // https://docs.microsoft.com/en-us/windows/win32/adschema/a-msds-useraccountdisabled
    private static final String ATTR_USER_ACCOUNT_DISABLED = "msDS-UserAccountDisabled";
    // for ADAM, replace the ADS_UF_PASSWORD_EXPIRED
    // https://docs.microsoft.com/en-us/windows/win32/adschema/a-msds-userpasswordexpired
    private static final String ATTR_USER_PASSWORD_EXPIRED = "msDS-UserPasswordExpired";
    private static final String ACCOUNT_DISABLED = "000001010000Z"; // other GeneralizedTime values indicate account is locked as of that time

    // https://docs.microsoft.com/en-us/windows/desktop/adschema/a-accountexpires
    // constant names follow the code in Iads.h
    private static final long ACCOUNT_NO_EXPIRATION = 0x7FFF_FFFF_FFFF_FFFFL;
    private static final int ADS_UF_DISABLED = 0x0002;
    private static final int ADS_UF_LOCK_OUT = 0x0010;
    private static final int ADS_DONT_EXPIRE_PASSWORD = 0x1_0000;
    private static final int ADS_UF_PASSWORD_EXPIRED = 0x80_0000;

    // https://ldapwiki.com/wiki/Administratively%20Disabled
    public static boolean checkIfUserEnabled(@NonNull Attributes user) {
        // Active Directory attributes
        Integer uac = getUserAccountControl(user);
        if (uac != null && (uac & ADS_UF_DISABLED) == ADS_UF_DISABLED) {
            return false;
        }
        String accountDisabled = getStringAttribute(user, ATTR_USER_ACCOUNT_DISABLED);
        if (accountDisabled != null) {
            return !Boolean.parseBoolean(accountDisabled);
        }
        // (Internet Draft) LDAP password policy attributes
        if (ACCOUNT_DISABLED.equals(getStringAttribute(user, ATTR_PWD_ACCOUNT_LOCKED_TIME))) {
            return false;
        }
        // EDirectory attributes
        String loginDisabled = getStringAttribute(user, ATTR_LOGIN_DISABLED);
        if (loginDisabled != null) {
            return !Boolean.parseBoolean(loginDisabled);
        }
        // Oracle attributes (they were documented on the wiki at least)
        String oracleIsEnabled = getStringAttribute(user, ATTR_ORACLE_IS_ENABLED);
        return oracleIsEnabled == null || oracleIsEnabled.equalsIgnoreCase("enabled");
    }

    // https://ldapwiki.com/wiki/Account%20Expiration
    public static boolean checkIfAccountNonExpired(@NonNull Attributes user) {
        // Active Directory attributes
        String accountExpirationDate = getStringAttribute(user, ATTR_ACCOUNT_EXPIRES);
        if (accountExpirationDate != null) {
            long expirationAsLong = Long.parseLong(accountExpirationDate);
            if (expirationAsLong == 0L || expirationAsLong == ACCOUNT_NO_EXPIRATION) {
                return true;
            }

            long nowIn100NsFromJan1601 = getWin32EpochHundredNanos();
            return expirationAsLong > nowIn100NsFromJan1601;
        }
        // (Internet Draft) LDAP password policy attributes
        GeneralizedTime now = GeneralizedTime.now();
        GeneralizedTime startTime = getGeneralizedTimeAttribute(user, ATTR_PWD_START_TIME);
        if (startTime != null && startTime.isAfter(now)) {
            return false;
        }
        GeneralizedTime endTime = getGeneralizedTimeAttribute(user, ATTR_PWD_END_TIME);
        if (endTime != null) {
            return endTime.isAfter(now);
        }
        // EDirectory attributes
        GeneralizedTime loginExpirationTime = getGeneralizedTimeAttribute(user, ATTR_LOGIN_EXPIRATION_TIME);
        return loginExpirationTime == null || loginExpirationTime.isAfter(now);
    }

    // https://ldapwiki.com/wiki/Password%20Expiration
    public static boolean checkIfCredentialsNonExpired(@NonNull Attributes user) {
        // Active Directory attributes
        Integer uac = getUserAccountControl(user);
        if (uac != null) {
            if ((uac & ADS_DONT_EXPIRE_PASSWORD) == ADS_DONT_EXPIRE_PASSWORD) {
                return true;
            }
            if ((uac & ADS_UF_PASSWORD_EXPIRED) == ADS_UF_PASSWORD_EXPIRED) {
                return false;
            }
        }
        String passwordExpired = getStringAttribute(user, ATTR_USER_PASSWORD_EXPIRED);
        return !Boolean.parseBoolean(passwordExpired);
    }

    // https://ldapwiki.com/wiki/Account%20Lockout
    // https://ldapwiki.com/wiki/Intruder%20Detection
    public static boolean checkIfAccountNonLocked(@NonNull Attributes user) {
        // Active Directory attributes
        Integer uac = getUserAccountControl(user);
        if (uac != null && (uac & ADS_UF_LOCK_OUT) == ADS_UF_LOCK_OUT) {
            return false;
        }
        // standard attributes
        String lockout = getStringAttribute(user, ATTR_PWD_LOCKOUT);
        if (lockout != null) {
            return !Boolean.parseBoolean(lockout);
        }
        // EDirectory attribute
        String lockedByIntruder = getStringAttribute(user, ATTR_LOCKED_BY_INTRUDER);
        return !Boolean.parseBoolean(lockedByIntruder);
    }

    // documentation: https://docs.microsoft.com/en-us/windows/desktop/adschema/a-accountexpires
    // code inspired by https://community.oracle.com/thread/1157460
    private static long getWin32EpochHundredNanos() {
        GregorianCalendar win32Epoch = new GregorianCalendar(1601, Calendar.JANUARY, 1);
        Date win32EpochDate = win32Epoch.getTime();
        // note that 1/1/1601 will be returned as a negative value by Java
        GregorianCalendar today = new GregorianCalendar();
        Date todayDate = today.getTime();
        long timeSinceWin32EpochInMs = todayDate.getTime() - win32EpochDate.getTime();
        // milliseconds to microseconds => x1000
        long timeSinceWin32EpochInNs = TimeUnit.NANOSECONDS.convert(timeSinceWin32EpochInMs, TimeUnit.MILLISECONDS);
        // but we need in 100 ns, as 1000 ns = 1 micro, add a x10 factor
        return timeSinceWin32EpochInNs * 100;
    }

    @CheckForNull
    private static Integer getUserAccountControl(@NonNull Attributes user) {
        String uac = getStringAttribute(user, ATTR_USER_ACCOUNT_CONTROL);
        String computedUac = getStringAttribute(user, ATTR_USER_ACCOUNT_CONTROL_COMPUTED);
        if (uac == null) {
            return computedUac == null ? null : Integer.parseInt(computedUac);
        } else if (computedUac == null) {
            return Integer.parseInt(uac);
        } else {
            return Integer.parseInt(uac) | Integer.parseInt(computedUac);
        }
    }

    @CheckForNull
    private static GeneralizedTime getGeneralizedTimeAttribute(@NonNull Attributes user, @NonNull String attrName) {
        String timestamp = getStringAttribute(user, attrName);
        try {
            return timestamp == null ? null : GeneralizedTime.parse(timestamp);
        } catch (ParseException e) {
            LDAPSecurityRealm.LOGGER.log(Level.WARNING, e, () ->
                    "Invalid format found parsing generalized time attribute " + attrName + " with value '" + timestamp + "'");
            return null;
        }
    }

    @CheckForNull
    private static String getStringAttribute(@NonNull Attributes user, @NonNull String attrName) {
        Attribute a = user.get(attrName);
        if (a == null || a.size() == 0) {
            return null;
        }
        try {
            Object v = a.get();
            return v == null ? null : v.toString();
        } catch (NamingException e) {
            return null;
        }
    }

    private UserAttributesHelper() {
        throw new UnsupportedOperationException();
    }
}