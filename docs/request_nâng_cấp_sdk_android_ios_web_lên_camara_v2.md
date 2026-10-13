# CAMARA Silent Authentication V2 - SDK Requirements and Implementation Plan

## Overview

CAMARA Silent Authentication V2 (CAMARA NV v2) is an open API standard under the CAMARA Project, developed in collaboration with Linux Foundation and GSMA Open Gateway. This technology enables applications to authenticate user phone numbers completely automatically (silent) directly through the mobile network without requiring SMS OTP or user interaction, eliminating phishing attack risks.

## Key Features of V2

### Core Differences from V1

#### V1 (Only Cellular Support):
- Required devices to turn off Wi-Fi and use cellular network (3G/4G/5G) for IP network detection and verification

#### V2 (Supports Both Wi-Fi & Cellular):
- Added SIM-based authentication via GSMA TS.43 temporary token
- Devices communicate with the operator's entitlement server at the OS level to get SIM token
- Works seamlessly even when Wi-Fi is active

## Technical Specifications

### A. Authentication & OAuth2 Flows

#### Connection Flows:
- **OIDC Authorization Code Flow**: 3-legged authentication with `prompt=none` parameter for silent operation
- **OIDC CIBA**: Client Initiated Backchannel Authentication in Poll Mode
- **OAuth2 Client Credentials Grant**: For client-to-server authentication
- **JWT Bearer Flow**: For secure token-based authentication

#### Client Authentication:
- **Mandatory**: Private key JWT for maximum security
- **Prohibited**: Common client_secret authentication

### B. Identity & Secure Transmission Channels

#### Login Hint (Identifier Formats):
- **tel**: E.164 formatted phone number (e.g., `tel:+8490xxxxxxx`)
- **ipport**: IPv4/IPv6 address with port from cellular network
- **operatortoken**: Token from SIM entitlement (GSMA TS.43)

#### Transport Security:
- **Minimum**: TLS 1.2 or higher
- **Anti-Forgery**: PKCE recommended for CSRF protection
- **Token Binding**: DPoP (RFC9449) support for binding access tokens to device keys

### C. Core API Endpoints

#### Two main POST endpoints:
1. **`/verify`**: Authenticates if the provided phone number matches the SIM being used
2. **`/device-phone-number`**: Returns the phone number associated with the granted access token (optional)

#### Request Example:
```json
{
  "phoneNumber": "+84901234567"
}
```

#### Response Example:
```json
{
  "devicePhoneNumberVerified": true
}
```

## Implementation Requirements

### 1. Mobile SDK Requirements

#### Android:
- Select correct cellular network when Wi-Fi is also active
- Must verify cellular capability; URLSession with requiredInterfaceType is not supported
- Handle fail-closed scenarios
- HTTP/TLS connection with certificate validation
- Timeout/retry logic for network errors
- Privacy: No logging of tokens, MSISDN, or sensitive SIM information

#### iOS:
- Verify cellular capability on real devices
- Handle fail-closed scenarios
- HTTP/TLS connection with certificate validation
- Timeout/retry logic for network errors
- Privacy: No logging of tokens, MSISDN, or sensitive SIM information

#### Web:
- Silent operation without user interaction
- No SMS OTP requests
- Integration with device OS for SIM-based authentication

### 2. Backend & Operator Integration

#### Mobile App / SDK:
- Select network or obtain TS.43 temporary token

#### Customer Backend:
- User session identification
- Authorization initiation
- Scope, token, and request validation
- Policy enforcement

#### CAMARA API Gateway / NV Service:
- Client authentication validation
- Request processing
- Subscriber mapping to MSISDN

#### Operator Identity / Network Systems:
- Subscriber authentication and MSISDN mapping
- Network-based authentication with reliable IP-to-MSISDN data
- SIM-based authentication with entitlement server, TS.43/ASAC, and compatible token flow

### 3. Testing & Interoperability

**Minimum requirements for CAMARA NV v2 compatibility declaration:**

- **API Contract**: OpenAPI contract validation, schema validation
- **Authentication**: `/verify` and `/device-phone-number` testing with correct/s incorrect/hashed phone numbers
- **Token Testing**: Expired, used, wrong scope, and incorrectly associated tokens
- **UX Testing**: No UI appearance or unwanted OTP requests
- **Platform Testing**: Android with Wi-Fi + 4G/5G, Wi-Fi-only, and cellular-only
- **iOS Testing**: Real device testing
- **SIM-based Testing**: TS.43/CIBA or JWT Bearer if SIM-based support is declared
- **Operator Testing**: Operator sandbox or live network with clear environment and results recording

## Implementation Plan

### Phase 1: Core Infrastructure Setup

#### 1.1 API Contract Implementation
- Implement OpenAPI contract for CAMARA NV v2
- Ensure `/verify` and `/device-phone-number` endpoints
- Implement proper request/response validation
- Add JWT-based client authentication (private key JWT)

#### 1.2 Authentication Flow Implementation
- Implement OIDC Authorization Code Flow with `prompt=none`
- Add support for CIBA Poll Mode
- Implement JWT Bearer Flow
- Add PKCE support for CSRF protection
- Add DPoP (RFC9449) support for token binding

#### 1.3 Mobile SDK Development

##### Android SDK:
- Implement cellular network selection when Wi-Fi is active
- Add connectivity verification using `ConnectivityManager.requestNetwork()`
- Implement proper HTTP client with cellular network binding
- Add comprehensive error handling and timeout logic
- Ensure privacy compliance (no sensitive data logging)

##### iOS SDK:
- Implement cellular capability verification on real devices
- Add proper socket binding to cellular interfaces
- Implement comprehensive error handling and timeout logic
- Ensure privacy compliance (no sensitive data logging)

##### Web SDK:
- Implement silent authentication flow
- Add integration with device OS for SIM-based authentication
- Ensure seamless operation without user interaction

#### 1.4 Backend Integration
- Implement customer backend for user session management and authorization
- Add CAMARA API Gateway for scope validation and token processing
- Implement operator integration for subscriber authentication
- Add network-based and SIM-based authentication support

### Phase 2: Testing & Validation

#### 2.1 API Contract Testing
- Validate OpenAPI schema
- Test `/verify` endpoint with various scenarios
- Test `/device-phone-number` endpoint
- Test authentication with correct/wrong/hashed phone numbers

#### 2.2 Authentication Testing
- Test single-use tokens with proper TTL (≤300s)
- Test token validation (not expired, not reused, correct scope)
- Test user context verification
- Test authorization flow

#### 2.3 Mobile SDK Testing
- Test Android SDK with various network conditions
- Test iOS SDK on real devices
- Test web SDK functionality
- Test fail-closed scenarios

#### 2.4 Integration Testing
- Test operator integration with sandbox and live environments
- Test network-based authentication
- Test SIM-based authentication (if supported)
- Test error handling and edge cases

### Phase 3: Documentation & Interoperability

#### 3.1 Documentation
- Document API endpoints and request/response formats
- Document authentication flows and requirements
- Document SDK usage for Android, iOS, and web
- Document operator integration requirements

#### 3.2 Interoperability
- Test with operator sandbox environments
- Document test results and environments
- Maintain list of operators supporting CAMARA NV v2
- Document any limitations or compatibility issues

## Key Technical Challenges

### 1. Cellular Network Binding
- **Challenge**: Ensure HTTP requests go through cellular network when Wi-Fi is also active
- **Android**: Use `ConnectivityManager.requestNetwork()` with `TRANSPORT_CELLULAR`
- **iOS**: Use low-level socket binding to cellular interfaces
- **Implementation**: Need to verify device is actually connected to cellular network

### 2. SIM-Based Authentication
- **Challenge**: Implement TS.43 temporary token generation and validation
- **Requirement**: Operator must support TS.43/ASAC for SIM-based authentication
- **Implementation**: Need integration with device OS for secure token generation

### 3. Fail-Closed Policy
- **Challenge**: System must fail closed when authentication cannot be verified
- **Implementation**: Comprehensive error handling and validation at every step
- **Requirement**: No authentication without proper verification

### 4. Privacy Compliance
- **Challenge**: Ensure no sensitive data is logged
- **Implementation**: Remove all logging of tokens, MSISDN, SIM information
- **Requirement**: Privacy by design in all components

## Technical Implementation Details

### Android Specific Implementation
```kotlin
// For Android, use ConnectivityManager to verify cellular network
ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
Network cellularNetwork = connectivityManager.getActiveNetworkForType(ConnectivityManager.TYPE_MOBILE);

// Then use this network to create HTTP client
OkHttpClient client = new OkHttpClient.Builder()
    .socketFactory(cellularNetwork.getSocketFactory())
    .build();
```

### iOS Specific Implementation
```objective-c
// For iOS, bind socket to cellular interface
socket_t sock = socket(AF_INET, SOCK_STREAM, 0);
struct sockaddr_in localAddress;
memset(&localAddress, 0, sizeof(localAddress));
localAddress.sin_len = sizeof(struct sockaddr_in);
localAddress.sin_family = AF_INET;
localAddress.sin_addr.s_addr = inet_addr("192.168.1.1"); // Replace with actual cellular IP

bind(sock, (struct sockaddr *)&localAddress, sizeof(localAddress));
```

### Web Implementation
```javascript
// For web, use API to get SIM temporary token from OS
navigator.getSimToken().then(token => {
  // Send token to backend for validation
  fetch('/api/verify', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`
    },
    body: JSON.stringify({ phoneNumber: phoneNumber })
  });
});
```

## Testing Strategy

### 3.1 Unit Testing
- Test individual components (authentication, validation, network binding)
- Mock external dependencies
- Test edge cases and error conditions

### 3.2 Integration Testing
- Test end-to-end authentication flows
- Test with operator sandbox
- Test with live environment
- Test various network conditions

### 3.3 Interoperability Testing
- Test with multiple operators
- Test different device types
- Test various network configurations
- Document test results and any issues

## Conclusion

CAMARA Silent Authentication V2 is a comprehensive authentication standard that supports both network-based and SIM-based authentication methods. Implementation requires careful consideration of:

1. **Technical Requirements**: Cellular network binding, SIM authentication, privacy compliance
2. **Authentication Flows**: OIDC, CIBA, JWT Bearer with proper security measures
3. **Mobile SDK Development**: Platform-specific implementations for Android, iOS, and web
4. **Operator Integration**: Proper integration with operator systems
5. **Testing & Validation**: Comprehensive testing across various environments

The implementation plan should be phased, starting with core API contract and authentication flows, followed by mobile SDK development, and finally integration testing with operators.

## References

- CAMARA Project Documentation
- GSMA TS.43 Specification
- OIDC (OpenID Connect) Specifications
- DPoP (RFC9449) Specification
- GSMA Open Gateway Specifications