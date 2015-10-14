/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.dialer.calllog;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteFullException;
import android.net.Uri;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.DisplayNameSources;
import android.provider.ContactsContract.PhoneLookup;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import com.android.contacts.common.util.Constants;
import com.android.contacts.common.util.PermissionsUtil;
import com.android.contacts.common.util.PhoneNumberHelper;
import com.android.contacts.common.util.UriUtils;
import com.android.dialer.service.CachedNumberLookupService;
import com.android.dialer.service.CachedNumberLookupService.CachedContactInfo;
import com.android.dialer.util.TelecomUtil;
import com.android.dialerbind.ObjectFactory;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Utility class to look up the contact information for a given number.
 */
public class ContactInfoHelper {
    private static final String TAG = ContactInfoHelper.class.getSimpleName();

    private final Context mContext;
    private final String mCurrentCountryIso;

    private static final CachedNumberLookupService mCachedNumberLookupService =
            ObjectFactory.newCachedNumberLookupService();

    public ContactInfoHelper(Context context, String currentCountryIso) {
        mContext = context;
        mCurrentCountryIso = currentCountryIso;
    }

    /**
     * Returns the contact information for the given number.
     * <p>
     * If the number does not match any contact, returns a contact info containing only the number
     * and the formatted number.
     * <p>
     * If an error occurs during the lookup, it returns null.
     *
     * @param number the number to look up
     * @param countryIso the country associated with this number
     */
    public ContactInfo lookupNumber(String number, String countryIso) {
        if (TextUtils.isEmpty(number)) {
            return null;
        }

        ContactInfo info;

        if (PhoneNumberHelper.isUriNumber(number)) {
            // The number is a SIP address..
            info = lookupContactFromUri(getContactInfoLookupUri(number));
            if (info == null || info == ContactInfo.EMPTY) {
                // If lookup failed, check if the "username" of the SIP address is a phone number.
                String username = PhoneNumberHelper.getUsernameFromUriNumber(number);
                if (PhoneNumberUtils.isGlobalPhoneNumber(username)) {
                    info = queryContactInfoForPhoneNumber(username, countryIso);
                }
            }
        } else {
            // Look for a contact that has the given phone number.
            info = queryContactInfoForPhoneNumber(number, countryIso);
        }

        final ContactInfo updatedInfo;
        if (info == null) {
            // The lookup failed.
            updatedInfo = null;
        } else {
            // If we did not find a matching contact, generate an empty contact info for the number.
            if (info == ContactInfo.EMPTY) {
                // Did not find a matching contact.
                updatedInfo = new ContactInfo();
                updatedInfo.number = number;
                updatedInfo.formattedNumber = formatPhoneNumber(number, null, countryIso);
                updatedInfo.normalizedNumber = PhoneNumberUtils.formatNumberToE164(
                        number, countryIso);
                updatedInfo.lookupUri = createTemporaryContactUri(updatedInfo.formattedNumber);
            } else {
                updatedInfo = info;
            }
        }
        return updatedInfo;
    }

    /**
     * Creates a JSON-encoded lookup uri for a unknown number without an associated contact
     *
     * @param number - Unknown phone number
     * @return JSON-encoded URI that can be used to perform a lookup when clicking on the quick
     *         contact card.
     */
    private static Uri createTemporaryContactUri(String number) {
        try {
            final JSONObject contactRows = new JSONObject().put(Phone.CONTENT_ITEM_TYPE,
                    new JSONObject().put(Phone.NUMBER, number).put(Phone.TYPE, Phone.TYPE_CUSTOM));

            final String jsonString = new JSONObject().put(Contacts.DISPLAY_NAME, number)
                    .put(Contacts.DISPLAY_NAME_SOURCE, DisplayNameSources.PHONE)
                    .put(Contacts.CONTENT_ITEM_TYPE, contactRows).toString();

            return Contacts.CONTENT_LOOKUP_URI
                    .buildUpon()
                    .appendPath(Constants.LOOKUP_URI_ENCODED)
                    .appendQueryParameter(ContactsContract.DIRECTORY_PARAM_KEY,
                            String.valueOf(Long.MAX_VALUE))
                    .encodedFragment(jsonString)
                    .build();
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Looks up a contact using the given URI.
     * <p>
     * It returns null if an error occurs, {@link ContactInfo#EMPTY} if no matching contact is
     * found, or the {@link ContactInfo} for the given contact.
     * <p>
     * The {@link ContactInfo#formattedNumber} field is always set to {@code null} in the returned
     * value.
     */
    public ContactInfo lookupContactFromUri(Uri uri) {
        if (uri == null) {
            return null;
        }
        if (!PermissionsUtil.hasContactsPermissions(mContext)) {
            return ContactInfo.EMPTY;
        }

        Cursor phoneLookupCursor = mContext.getContentResolver().query(uri,
                PhoneQuery.PHONE_LOOKUP_PROJECTION, null, null, null);

        if (phoneLookupCursor == null) {
            return null;
        }

        try {
            if (!phoneLookupCursor.moveToFirst()) {
                return ContactInfo.EMPTY;
            }
            String lookupKey = phoneLookupCursor.getString(PhoneQuery.LOOKUP_KEY);
            ContactInfo contactInfo = createPhoneLookupContactInfo(phoneLookupCursor, lookupKey);
            contactInfo.nameAlternative = lookUpDisplayNameAlternative(mContext, lookupKey);
            return contactInfo;
        } finally {
            phoneLookupCursor.close();
        }
    }

    private ContactInfo createPhoneLookupContactInfo(Cursor phoneLookupCursor, String lookupKey) {
        ContactInfo info = new ContactInfo();
        info.lookupKey = lookupKey;
        info.lookupUri = Contacts.getLookupUri(phoneLookupCursor.getLong(PhoneQuery.PERSON_ID),
                lookupKey);
        info.name = phoneLookupCursor.getString(PhoneQuery.NAME);
        info.type = phoneLookupCursor.getInt(PhoneQuery.PHONE_TYPE);
        info.label = phoneLookupCursor.getString(PhoneQuery.LABEL);
        info.number = phoneLookupCursor.getString(PhoneQuery.MATCHED_NUMBER);
        info.normalizedNumber = phoneLookupCursor.getString(PhoneQuery.NORMALIZED_NUMBER);
        info.photoId = phoneLookupCursor.getLong(PhoneQuery.PHOTO_ID);
        info.photoUri = UriUtils.parseUriOrNull(phoneLookupCursor.getString(PhoneQuery.PHOTO_URI));
        info.formattedNumber = null;
        return info;
    }

    public static String lookUpDisplayNameAlternative(Context context, String lookupKey) {
        if (lookupKey == null) {
            return null;
        }

        Uri uri = Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, lookupKey);

        Cursor cursor = context.getContentResolver().query(uri,
                PhoneQuery.DISPLAY_NAME_ALTERNATIVE_PROJECTION, null, null, null);

        if (cursor == null) {
            return null;
        }

        try {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return cursor.getString(PhoneQuery.NAME_ALTERNATIVE);
        } finally {
            cursor.close();
        }
    }

    /**
     * Determines the contact information for the given phone number.
     * <p>
     * It returns the contact info if found.
     * <p>
     * If no contact corresponds to the given phone number, returns {@link ContactInfo#EMPTY}.
     * <p>
     * If the lookup fails for some other reason, it returns null.
     */
    private ContactInfo queryContactInfoForPhoneNumber(String number, String countryIso) {
        if (TextUtils.isEmpty(number)) {
            return null;
        }

        ContactInfo info = lookupContactFromUri(getContactInfoLookupUri(number));
        if (info != null && info != ContactInfo.EMPTY) {
            info.formattedNumber = formatPhoneNumber(number, null, countryIso);
        } else if (mCachedNumberLookupService != null) {
            CachedContactInfo cacheInfo =
                    mCachedNumberLookupService.lookupCachedContactFromNumber(mContext, number);
            if (cacheInfo != null) {
                info = cacheInfo.getContactInfo().isBadData ? null : cacheInfo.getContactInfo();
            } else {
                info = null;
            }
        }
        return info;
    }

    /**
     * Format the given phone number
     *
     * @param number the number to be formatted.
     * @param normalizedNumber the normalized number of the given number.
     * @param countryIso the ISO 3166-1 two letters country code, the country's convention will be
     *        used to format the number if the normalized phone is null.
     *
     * @return the formatted number, or the given number if it was formatted.
     */
    private String formatPhoneNumber(String number, String normalizedNumber, String countryIso) {
        if (TextUtils.isEmpty(number)) {
            return "";
        }
        // If "number" is really a SIP address, don't try to do any formatting at all.
        if (PhoneNumberHelper.isUriNumber(number)) {
            return number;
        }
        if (TextUtils.isEmpty(countryIso)) {
            countryIso = mCurrentCountryIso;
        }
        return PhoneNumberUtils.formatNumber(number, normalizedNumber, countryIso);
    }

    /**
     * Stores differences between the updated contact info and the current call log contact info.
     *
     * @param number The number of the contact.
     * @param countryIso The country associated with this number.
     * @param updatedInfo The updated contact info.
     * @param callLogInfo The call log entry's current contact info.
     */
    public void updateCallLogContactInfo(String number, String countryIso, ContactInfo updatedInfo,
            ContactInfo callLogInfo) {
        if (!PermissionsUtil.hasPermission(mContext, android.Manifest.permission.WRITE_CALL_LOG)) {
            return;
        }

        final ContentValues values = new ContentValues();
        boolean needsUpdate = false;

        if (callLogInfo != null) {
            if (!TextUtils.equals(updatedInfo.name, callLogInfo.name)) {
                values.put(Calls.CACHED_NAME, updatedInfo.name);
                needsUpdate = true;
            }

            if (updatedInfo.type != callLogInfo.type) {
                values.put(Calls.CACHED_NUMBER_TYPE, updatedInfo.type);
                needsUpdate = true;
            }

            if (!TextUtils.equals(updatedInfo.label, callLogInfo.label)) {
                values.put(Calls.CACHED_NUMBER_LABEL, updatedInfo.label);
                needsUpdate = true;
            }

            if (!UriUtils.areEqual(updatedInfo.lookupUri, callLogInfo.lookupUri)) {
                values.put(Calls.CACHED_LOOKUP_URI, UriUtils.uriToString(updatedInfo.lookupUri));
                needsUpdate = true;
            }

            // Only replace the normalized number if the new updated normalized number isn't empty.
            if (!TextUtils.isEmpty(updatedInfo.normalizedNumber) &&
                    !TextUtils.equals(updatedInfo.normalizedNumber, callLogInfo.normalizedNumber)) {
                values.put(Calls.CACHED_NORMALIZED_NUMBER, updatedInfo.normalizedNumber);
                needsUpdate = true;
            }

            if (!TextUtils.equals(updatedInfo.number, callLogInfo.number)) {
                values.put(Calls.CACHED_MATCHED_NUMBER, updatedInfo.number);
                needsUpdate = true;
            }

            if (updatedInfo.photoId != callLogInfo.photoId) {
                values.put(Calls.CACHED_PHOTO_ID, updatedInfo.photoId);
                needsUpdate = true;
            }

            final Uri updatedPhotoUriContactsOnly =
                    UriUtils.nullForNonContactsUri(updatedInfo.photoUri);
            if (!UriUtils.areEqual(updatedPhotoUriContactsOnly, callLogInfo.photoUri)) {
                values.put(Calls.CACHED_PHOTO_URI,
                        UriUtils.uriToString(updatedPhotoUriContactsOnly));
                needsUpdate = true;
            }

            if (!TextUtils.equals(updatedInfo.formattedNumber, callLogInfo.formattedNumber)) {
                values.put(Calls.CACHED_FORMATTED_NUMBER, updatedInfo.formattedNumber);
                needsUpdate = true;
            }
        } else {
            // No previous values, store all of them.
            values.put(Calls.CACHED_NAME, updatedInfo.name);
            values.put(Calls.CACHED_NUMBER_TYPE, updatedInfo.type);
            values.put(Calls.CACHED_NUMBER_LABEL, updatedInfo.label);
            values.put(Calls.CACHED_LOOKUP_URI, UriUtils.uriToString(updatedInfo.lookupUri));
            values.put(Calls.CACHED_MATCHED_NUMBER, updatedInfo.number);
            values.put(Calls.CACHED_NORMALIZED_NUMBER, updatedInfo.normalizedNumber);
            values.put(Calls.CACHED_PHOTO_ID, updatedInfo.photoId);
            values.put(Calls.CACHED_PHOTO_URI, UriUtils.uriToString(
                    UriUtils.nullForNonContactsUri(updatedInfo.photoUri)));
            values.put(Calls.CACHED_FORMATTED_NUMBER, updatedInfo.formattedNumber);
            needsUpdate = true;
        }

        if (!needsUpdate) {
            return;
        }

        try {
            if (countryIso == null) {
                mContext.getContentResolver().update(
                        TelecomUtil.getCallLogUri(mContext),
                        values,
                        Calls.NUMBER + " = ? AND " + Calls.COUNTRY_ISO + " IS NULL",
                        new String[]{ number });
            } else {
                mContext.getContentResolver().update(
                        TelecomUtil.getCallLogUri(mContext),
                        values,
                        Calls.NUMBER + " = ? AND " + Calls.COUNTRY_ISO + " = ?",
                        new String[]{ number, countryIso });
            }
        } catch (SQLiteFullException e) {
            Log.e(TAG, "Unable to update contact info in call log db", e);
        }
    }

    public static Uri getContactInfoLookupUri(String number) {
        // Get URI for the number in the PhoneLookup table, with a parameter to indicate whether
        // the number is a SIP number.
        return PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI.buildUpon()
                .appendPath(Uri.encode(number))
                .appendQueryParameter(PhoneLookup.QUERY_PARAMETER_SIP_ADDRESS,
                        String.valueOf(PhoneNumberHelper.isUriNumber(number)))
                .build();
    }

    /**
     * Returns the contact information stored in an entry of the call log.
     *
     * @param c A cursor pointing to an entry in the call log.
     */
    public static ContactInfo getContactInfo(Cursor c) {
        ContactInfo info = new ContactInfo();
        info.lookupUri = UriUtils.parseUriOrNull(c.getString(CallLogQuery.CACHED_LOOKUP_URI));
        info.name = c.getString(CallLogQuery.CACHED_NAME);
        info.type = c.getInt(CallLogQuery.CACHED_NUMBER_TYPE);
        info.label = c.getString(CallLogQuery.CACHED_NUMBER_LABEL);
        String matchedNumber = c.getString(CallLogQuery.CACHED_MATCHED_NUMBER);
        String postDialDigits = PhoneNumberDisplayUtil.canShowPostDial()
                ? c.getString(CallLogQuery.POST_DIAL_DIGITS) : "";
        info.number = (matchedNumber == null) ?
                c.getString(CallLogQuery.NUMBER) + postDialDigits : matchedNumber;

        info.normalizedNumber = c.getString(CallLogQuery.CACHED_NORMALIZED_NUMBER);
        info.photoId = c.getLong(CallLogQuery.CACHED_PHOTO_ID);
        info.photoUri = UriUtils.nullForNonContactsUri(
                UriUtils.parseUriOrNull(c.getString(CallLogQuery.CACHED_PHOTO_URI)));
        info.formattedNumber = c.getString(CallLogQuery.CACHED_FORMATTED_NUMBER);

        return info;
    }

    /**
     * Given a contact's sourceType, return true if the contact is a business
     *
     * @param sourceType sourceType of the contact. This is usually populated by
     *        {@link #mCachedNumberLookupService}.
     */
    public boolean isBusiness(int sourceType) {
        return mCachedNumberLookupService != null
                && mCachedNumberLookupService.isBusiness(sourceType);
    }

    /**
     * This function looks at a contact's source and determines if the user can
     * mark caller ids from this source as invalid.
     *
     * @param sourceType The source type to be checked
     * @param objectId The ID of the Contact object.
     * @return true if contacts from this source can be marked with an invalid caller id
     */
    public boolean canReportAsInvalid(int sourceType, String objectId) {
        return mCachedNumberLookupService != null
                && mCachedNumberLookupService.canReportAsInvalid(sourceType, objectId);
    }
}
