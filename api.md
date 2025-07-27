# API Services Documentation

## Overview
This document describes the usage of three main API services in the ClashMetaForAndroid application:
- **TokenApiService**: Converts subscription tokens to profile URLs
- **ContactApiService**: Retrieves contact phone numbers from multiple endpoints
- **TokenInfoService**: Gets token information including data and time remaining

---

## 1. TokenApiService

### Purpose
Converts subscription tokens into profile URLs that can be used to download VPN configurations.

### Key Features
- **Multiple Fallback URLs**: Uses 3 different API endpoints for reliability
- **Retry Logic**: Each URL is tried up to 2 times before moving to the next
- **Enhanced Timeouts**: 30s total timeout with proper connection management
- **Token Validation**: Validates token format before making requests
- **Comprehensive Error Handling**: Specific error messages for different failure scenarios

### Usage

```kotlin
import com.dualeo.service.app.api.TokenApiService

// Initialize service
val tokenApiService = TokenApiService()

// Get profile URL from token
suspend fun getProfileUrl(token: String) {
    val result = tokenApiService.getProfileUrl(token)
    
    result.fold(
        onSuccess = { profileUrl ->
            println("Profile URL: $profileUrl")
            // Use the profile URL to download configuration
        },
        onFailure = { error ->
            println("Error: ${error.message}")
        }
    )
}
```

### Token Requirements
- **Minimum Length**: 8 characters
- **Format**: Alphanumeric characters only (`[a-zA-Z0-9]+`)
- **Not Empty**: Token cannot be blank

### API Endpoints Used
1. `https://link.xn--iiq451n.com/api/index.php?token={token}` (Primary)

### Response Handling
The service looks for URLs in JSON responses under these keys:
- `url`
- `subscribe_url`
- `subscription_url`
- `link`
- `data.url`
- `data.subscribe_url`
- `data.subscription_url`

If JSON parsing fails, it searches for any valid HTTP/HTTPS URL in the response text.

### Error Codes
- **404**: Token doesn't exist
- **401**: Invalid token
- **403**: Token expired
- **500**: Internal server error

---

## 2. ContactApiService

### Purpose
Retrieves contact phone numbers from multiple API endpoints with caching and fallback support.

### Key Features
- **24-Hour Caching**: Successful responses are cached for 24 hours
- **Multiple Endpoints**: Uses 4 different sources for phone numbers
- **Fast Timeouts**: 1-second connection timeout to prevent UI blocking
- **Cache Fallback**: Returns cached data if all endpoints fail
- **Phone Number Validation**: Filters valid phone numbers (8-15 digits)

### Usage

```kotlin
import com.dualeo.service.app.api.ContactApiService
import com.dualeo.service.app.api.ContactInfo

// Get contact information (static method)
suspend fun getContactInfo() {
    val contactInfo = ContactApiService.getContactInfo()
    
    if (contactInfo != null) {
        println("Found ${contactInfo.phones.size} phone numbers")
        contactInfo.phones.forEach { phone ->
            println("Phone: $phone")
        }
    } else {
        println("No contact information available")
    }
}

// Format phones for display
fun formatContactDisplay(contactInfo: ContactInfo?) {
    val service = ContactApiService()
    val displayText = if (contactInfo != null) {
        service.formatContactText(contactInfo.phones)
    } else {
        "Chưa có số liên hệ"
    }
    println(displayText)
}

// Clear cache if needed
fun clearContactCache() {
    val service = ContactApiService()
    service.clearCache()
}
```

### API Endpoints Used
1. `https://link.xn--iiq451n.com/contact/phone_api_1.php` (Primary, used 3 times)
2. `https://raw.githubusercontent.com/Phamtuananhh2k5/phone_number/refs/heads/main/number.txt` (GitHub backup)

### Response Format
Expected response format (text with phone numbers):
```
0901234567
0987654321,0123456789
0912345678
```

Phone numbers can be separated by:
- Commas (`,`)
- Newlines (`\n`)
- Carriage returns (`\r`)

### Validation Rules
- Phone numbers must be 8-15 digits
- Only numeric characters allowed
- Leading zeros are preserved

### Cache Management
- **Cache Duration**: 24 hours (86,400,000 ms)
- **Cache Key**: Stored in memory (not persistent)
- **Cache Strategy**: Return cached data on any error if available

---

## 3. TokenInfoService

### Purpose
Retrieves detailed information about a subscription token including remaining data and time.

### Key Features
- **Profile URL Support**: Extracts token from profile URLs automatically
- **Unlimited Detection**: Properly handles unlimited subscriptions
- **Fast Timeouts**: 5-second timeouts for quick response
- **Token Extraction**: Supports multiple URL patterns for token extraction

### Usage

```kotlin
import com.dualeo.service.app.api.TokenInfoService
import com.dualeo.service.app.api.TokenInfo

// Initialize service
val tokenInfoService = TokenInfoService()

// Get token info from profile URL
suspend fun getTokenInfo(profileUrl: String) {
    val result = tokenInfoService.getTokenInfoFromProfileUrl(profileUrl)
    
    result.fold(
        onSuccess = { tokenInfo ->
            if (tokenInfo.success) {
                println("Data remaining: ${tokenInfo.dataRemaining}")
                
                if (tokenInfo.isUnlimited) {
                    println("Subscription: Unlimited")
                } else {
                    println("Days remaining: ${tokenInfo.daysRemaining}")
                }
            } else {
                println("Token info request failed")
            }
        },
        onFailure = { error ->
            println("Error: ${error.message}")
        }
    )
}
```

### TokenInfo Data Class
```kotlin
data class TokenInfo(
    val success: Boolean = false,          // API call success status
    val dataRemaining: String = "",        // Remaining data (e.g., "5.2 GB")
    val daysRemaining: Int? = null,        // Days left (null = unlimited)
    val isUnlimited: Boolean = false       // True if unlimited subscription
)
```

### API Endpoint
- `https://link.xn--iiq451n.com/token_info/token_info.php?token={token}`

### Token Extraction Patterns
The service can extract tokens from URLs using these patterns:
1. `token=([a-fA-F0-9]+)` - Query parameter format
2. `client/([a-fA-F0-9]+)` - Path parameter format
3. `/([a-fA-F0-9]{32,})` - Any 32+ character hex string in path

### Response Format
Expected JSON response:
```json
{
    "success": true,
    "data_remaining": "5.2 GB",
    "days_remaining": 30
}
```

For unlimited subscriptions:
```json
{
    "success": true,
    "data_remaining": "Unlimited",
    "days_remaining": null
}
```

---

## Integration Examples

### Complete Token Workflow
```kotlin
class TokenWorkflow {
    private val tokenApiService = TokenApiService()
    private val tokenInfoService = TokenInfoService()
    
    suspend fun processToken(token: String): TokenResult {
        // Step 1: Get profile URL
        val urlResult = tokenApiService.getProfileUrl(token)
        if (urlResult.isFailure) {
            return TokenResult.Error("Failed to get profile URL: ${urlResult.exceptionOrNull()?.message}")
        }
        
        val profileUrl = urlResult.getOrNull()!!
        
        // Step 2: Get token information
        val infoResult = tokenInfoService.getTokenInfoFromProfileUrl(profileUrl)
        if (infoResult.isFailure) {
            return TokenResult.Error("Failed to get token info: ${infoResult.exceptionOrNull()?.message}")
        }
        
        val tokenInfo = infoResult.getOrNull()!!
        
        return TokenResult.Success(profileUrl, tokenInfo)
    }
}

sealed class TokenResult {
    data class Success(val profileUrl: String, val tokenInfo: TokenInfo) : TokenResult()
    data class Error(val message: String) : TokenResult()
}
```

### Contact Display Integration
```kotlin
class ContactDisplay {
    private lateinit var contactInfo: ContactInfo
    
    suspend fun loadAndDisplayContacts() {
        val contact = ContactApiService.getContactInfo()
        if (contact != null) {
            contactInfo = contact
            updateUI()
        }
    }
    
    private fun updateUI() {
        val service = ContactApiService()
        val displayText = service.formatContactText(contactInfo.phones)
        // Update your UI with displayText
    }
}
```

---

## Error Handling Best Practices

### 1. Network Timeouts
All services implement proper timeouts to prevent UI blocking:
- **TokenApiService**: 30s total timeout with 25s per request
- **ContactApiService**: 3s total timeout with 1s per connection
- **TokenInfoService**: 10s total timeout with 5s per connection

### 2. Fallback Strategies
- **TokenApiService**: Multiple URLs with retry logic
- **ContactApiService**: Multiple endpoints with cache fallback
- **TokenInfoService**: Single endpoint with timeout protection

### 3. Error Messages
All services provide descriptive error messages in Vietnamese for better user experience.

---

## Performance Considerations

### Caching
- **ContactApiService**: 24-hour memory cache
- **TokenApiService**: No caching (URLs may change)
- **TokenInfoService**: No caching (real-time data needed)

### Threading
All services use `Dispatchers.IO` for network operations and are safe to call from any coroutine context.

### Memory Usage
- ContactApiService maintains minimal cache (list of phone numbers)
- Other services have no persistent memory usage

---

## Testing

### Unit Test Example
```kotlin
@Test
fun testTokenApiService() = runBlocking {
    val service = TokenApiService()
    val result = service.getProfileUrl("validtesttoken123")
    
    assertTrue(result.isSuccess)
    val url = result.getOrNull()
    assertNotNull(url)
    assertTrue(url!!.startsWith("http"))
}
```

### Integration Test Example
```kotlin
@Test
fun testContactApiService() = runBlocking {
    val contactInfo = ContactApiService.getContactInfo()
    // Should work even if network fails (cache fallback)
    // assertNotNull(contactInfo) // May be null if no cache and network fails
}
```

---

## Changelog

### Version 1.0
- Initial implementation of all three services
- Multiple fallback URLs for TokenApiService
- 24-hour caching for ContactApiService
- Token extraction for TokenInfoService
- Comprehensive error handling
- Performance optimizations with proper timeouts
