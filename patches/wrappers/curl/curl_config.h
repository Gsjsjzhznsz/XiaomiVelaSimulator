/* Minimal curl_config.h for NuttX (qemu-armv7a, BSD sockets, no TLS)
 * Handwritten for the vela simulator firmware wrapper. */

#define OS "NuttX"

/* sizes (armv7-a 32-bit) */
#define SIZEOF_INT 4
#define SIZEOF_LONG 4
#define SIZEOF_LONG_LONG 8
#define SIZEOF_SIZE_T 4
#define SIZEOF_OFF_T 4
#define SIZEOF_TIME_T 4
#define SIZEOF_VOIDP 4
#define SIZEOF_STRUCT_IN6_ADDR 16
#define SIZEOF_STRUCT_SOCKADDR_IN6 28
#define SIZEOF_STRUCT_SOCKADDR_STORAGE 128

#define HAVE_SYS_SOCKET_H 1
#define HAVE_SYS_SELECT_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_IOCTL_H 1
#define HAVE_UNISTD_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_MEMORY_H 1
#define HAVE_FCNTL_H 1
#define HAVE_ERRNO_H 1
#define HAVE_STDBOOL_H 1
#define HAVE_POLL_H 1
#define HAVE_NETDB_H 1
#define HAVE_NETINET_IN_H 1
#define HAVE_NETINET_TCP_H 1
#define HAVE_ARPA_INET_H 1
#define HAVE_TIME_H 1

#define HAVE_SOCKET 1
#define HAVE_SELECT 1
#define HAVE_POLL_FINE 1
#define HAVE_FCNTL 1
#define HAVE_IOCTL 1
#define HAVE_STRDUP 1
#define HAVE_STRSTR 1
#define HAVE_STRCASECMP 1
#define HAVE_STRNCASECMP 1
#define HAVE_SNPRINTF 1
#define HAVE_MEMRCHR 1
#define HAVE_GETTIMEOFDAY 1
#define HAVE_GMTIME_R 1
#define HAVE_LOCALTIME_R 1
#define HAVE_ALARM 1
#define HAVE_UNAME 1

#define HAVE_STRUCT_TIMEVAL 1
#define HAVE_STRUCT_SOCKADDR_STORAGE 1
#define HAVE_BOOL_T 1

#define RETSIGTYPE void

/* feature trims: keep plain HTTP over BSD sockets only */
#define CURL_DISABLE_LDAP 1
#define CURL_DISABLE_LDAPS 1
#define CURL_DISABLE_TELNET 1
#define CURL_DISABLE_DICT 1
#define CURL_DISABLE_FILE 1
#define CURL_DISABLE_TFTP 1
#define CURL_DISABLE_RTSP 1
#define CURL_DISABLE_POP3 1
#define CURL_DISABLE_IMAP 1
#define CURL_DISABLE_SMTP 1
#define CURL_DISABLE_SMB 1
#define CURL_DISABLE_GOPHER 1
#define CURL_DISABLE_MQTT 1
#define CURL_DISABLE_FTP 1
#define CURL_DISABLE_PROXY 1
#define CURL_DISABLE_NETRC 1
#define CURL_DISABLE_COOKIES 1
#define CURL_DISABLE_ALTSVC 1
#define CURL_DISABLE_HSTS 1
#define CURL_DISABLE_DOH 1
#define CURL_DISABLE_MIME 1
#define CURL_DISABLE_GETOPTIONS 1
#define CURL_DISABLE_KRB5 1
#define CURL_DISABLE_AWS 1
#define CURL_DISABLE_SHA512_256 1
#define CURL_DISABLE_IPFS 1

#define CURL_DISABLE_CRYPTO_AUTH 1

/* no threading primitives needed */
#define CURL_MTUILDFUNCTION 0

/* resolver: NuttX provides getaddrinfo */
#define HAVE_GETADDRINFO 1
#define HAVE_GETADDRINFO_THREADSAFE 1
#define USE_IPV6 0

#define HAVE_RECV 1
#define HAVE_SEND 1
#define RECV_TYPE_ARG1 int
#define RECV_TYPE_ARG2 void *
#define RECV_TYPE_ARG3 size_t
#define RECV_TYPE_ARG4 int
#define RECV_TYPE_RETV ssize_t
#define SEND_TYPE_ARG1 int
#define SEND_TYPE_ARG2 void *
#define SEND_TYPE_ARG3 size_t
#define SEND_TYPE_ARG4 int
#define SEND_TYPE_RETV ssize_t
#define SEND_QUAL_ARG2 const

/* 64-bit curl_off_t (NuttX off_t is 32-bit) */
#define CURL_TYPEOF_CURL_OFF_T long long
#define CURL_FORMAT_CURL_OFF_T "lld"
#define CURL_FORMAT_CURL_OFF_TU "llu"
#define CURL_SUFFIX_CURL_OFF_T LL
#define CURL_SUFFIX_CURL_OFF_TU ULL
#define CURL_TYPEOF_CURL_SOCKLEN_T socklen_t
#define CURL_TYPEOF_CURL_SOCKET_T int
#define SIZEOF_CURL_OFF_T 8
#define SIZEOF_CURL_SOCKLEN_T 4
#define HAVE_FCNTL_O_NONBLOCK 1
#define HAVE_LONGLONG 1
