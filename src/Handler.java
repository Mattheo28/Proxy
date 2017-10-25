import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Handler extends Thread {

    //Pattern to be used by Matcher to check if a request is an HTTPS request.
    private static final Pattern HTTPS_PATTERN = Pattern.compile("CONNECT (.+):(.+) HTTP/(1\\.[01])",
            Pattern.CASE_INSENSITIVE);
    private final Socket clientSocket;
    //Maximum number of bytes in buffer.
    private static final int BUFFER_SIZE = 32768;
    private static final int MAX_CACHE_SIZE = 10000;
    private static LinkedHashMap<String, byte[]> cache = new LinkedHashMap<>();
    private int cacheIndex = 0;

    /**
     * Constructor for the Handler class, assigns the client socket to be used.
     *
     * @param clientSocket the client socket to be used.
     */
    public Handler(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        DataOutputStream out = null;
        BufferedReader in = null;

        try {
            //Get input and output streams for the client.
            out = new DataOutputStream(clientSocket.getOutputStream());
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            String urlToCall;
            String request = getRequest(in);

            if (!request.equals("")) {
                try {
                    StringTokenizer tokenizer = new StringTokenizer(request);
                    tokenizer.nextToken();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                String[] tokens = request.split(" ");
                //url will always be the second token in the request.
                urlToCall = tokens[1];


                //Meaningful output, controlled by the if statement.
                if (urlToCall.substring(urlToCall.length() - 1).equals("/")) {
                    System.out.println("Request for : " + urlToCall);
                }

                //Get the url without the protocol, used by the cache.
                String urlWithoutProtocol = urlToCall.replace("https://", "");
                urlWithoutProtocol = urlWithoutProtocol.replace("http://", "");

                //Send data from the cache if the url is already there.
                if (cache.containsKey(urlWithoutProtocol) && !request.contains("CONNECT")) {
                    sendDataFromCache(urlWithoutProtocol, out);
                } else {
                    //Check if the request is an HTTPS request.
                    Matcher matcher = HTTPS_PATTERN.matcher(request);
                    if (matcher.matches()) {
                        sendHTTPSRequest(matcher, urlToCall, urlWithoutProtocol, clientSocket.getOutputStream());
                    } else {
                        try {
                            //First try and make it into an HTTPS request.
                            urlToCall = urlToCall.replace("http", "https");
                            URL url = new URL(urlToCall);
                            URLConnection connection = url.openConnection();

                            // Get the response code.
                            HttpsURLConnection httpsConn = (HttpsURLConnection) connection;
                            if (httpsConn.getResponseCode() == 200) {
                                //Successful switch to HTTPS, so send this request's response.
                                sendData(urlWithoutProtocol, "https://", httpsConn.getInputStream(), out);
                            } else {
                                //HTTPS request failed, so send an HTTP request.
                                if (urlToCall.substring(urlToCall.length() - 1).equals("/")) {
                                    System.out.println("HTTPS not available - trying HTTP request.");
                                }
                                sendHTTPRequest(urlToCall, urlWithoutProtocol, out);
                            }
                        } catch (ConnectException e) {
                            //If connection is refused, try again by sending an HTTP request.
                            if (urlToCall.substring(urlToCall.length() - 1).equals("/")) {
                                System.out.println("Connection failed. Trying again...");
                            }
                            sendHTTPRequest(urlToCall, urlWithoutProtocol, out);
                        } catch (Exception e) {
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                //close the resources.
                if (out != null) {
                    out.close();
                }
                if (in != null) {
                    in.close();
                }
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Goes through the input from the client socket and gets the client's request.
     *
     * @param in the input stream from the client socket.
     * @return the client's request.
     */
    private String getRequest(BufferedReader in) {
        String inputLine;
        String request = "";
        try {
            //begin get request from client. Only first line needed to get request.
            if ((inputLine = in.readLine()) != null) {
                request = inputLine;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return request;
    }

    /**
     * Method which tries to send an HTTPS request.
     *
     * @param matcher            the HTTPS pattern matcher.
     * @param urlToCall          the url in the client's request.
     * @param urlWithoutProtocol the same url without the protocol, to be used by the cache.
     * @param outputStream       where to write the request.
     */
    private void sendHTTPSRequest(Matcher matcher, String urlToCall, String urlWithoutProtocol,
                                  OutputStream outputStream) {
        try {
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream, "ISO-8859-1");

            final Socket socket;
            try {
                //Socket used to send request to the server.
                socket = new Socket(matcher.group(1), Integer.parseInt(matcher.group(2)));
            } catch (IOException | NumberFormatException e) {
                //If socket failed to open, try again by sending an HTTP request instead.
                sendHTTPRequest(urlToCall, urlWithoutProtocol, outputStream);
                outputStreamWriter.flush();
                return;
            }
            try {
                //Meaningful output, connection is successful.
                outputStreamWriter.write("HTTP/" + matcher.group(3) + " 200 Connection established\r\n");
                outputStreamWriter.write("Proxy-agent: Simple/0.1\r\n");
                outputStreamWriter.write("\r\n");
                outputStreamWriter.flush();

                //Use threads for request to improve efficiency and reduce time wasting, by serving
                // requests simultaneously.
                Thread clientToServer = new Thread() {
                    @Override
                    public void run() {
                        try {
                            sendData(urlWithoutProtocol, "https://", socket.getInputStream(),
                                    clientSocket.getOutputStream());
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                };
                clientToServer.start();
                try {
                    //Send the data from the HTTPS request.
                    sendData(urlWithoutProtocol, "https://", clientSocket.getInputStream(),
                            socket.getOutputStream());
                } finally {
                    try {
                        clientToServer.join();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            } finally {
                //close the resources.
                clientSocket.close();
                socket.close();
            }
        } catch (Exception e) {
        }
    }

    /**
     * Method which changes the request to be an HTTP request, and then makes the request.
     *
     * @param url                the url in the request.
     * @param urlWithoutProtocol the url without the protocol to be used by the cache.
     * @param out                the output stream where the response should be written.
     */
    private void sendHTTPRequest(String url, String urlWithoutProtocol, OutputStream out) {
        try {
            //Make the request into an HTTP request.
            url = url.replace("https", "http");
            url = url.replace(":443", "");
            //Add the protocol if it doesn't have one.
            if (!url.contains("http")) {
                url = "http://" + url;
            }
            //Open an HTTP connection.
            URL httpUrl = new URL(url);
            URLConnection urlConnection = httpUrl.openConnection();
            HttpURLConnection httpConn = (HttpURLConnection) urlConnection;
            //Send the HTTP request's response back to the client.
            sendData(urlWithoutProtocol, "http://", httpConn.getInputStream(), out);
        } catch (ConnectException e) {
            System.out.println("Problem encountered while trying to connect to the server. Connection was refused.");
        } catch (MalformedURLException e) {
            System.out.println("The url: " + url + " is malformed. HTTP Request failed.");
        } catch (Exception e) {
        }
    }

    /**
     * Method which sends bytes from the cache and doesn't make any requests.
     *
     * @param url          the url in the request from the client.
     * @param outputStream the output stream in which the bytes should be written.
     */
    private void sendDataFromCache(String url, OutputStream outputStream) {
        byte outputBytes[] = cache.get(url);
        ByteArrayInputStream bis = new ByteArrayInputStream(outputBytes);
        sendData(url, "", bis, outputStream);
    }

    /**
     * Method which sends the response back to the client, from a successful request.
     *
     * @param url          the url in the request from the client.
     * @param protocol     the protocol used in the request (used only for meaningful output).
     * @param inputStream  the input stream to read the bytes of data from.
     * @param outputStream the output stream to write the bytes of data to the client.
     */
    private void sendData(String url, String protocol, InputStream inputStream, OutputStream outputStream) {
        try {
            if (inputStream != null) {
                //ByteArrayOutputStream used to write to the cache.
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                //Boolean for if the url is already in the cache.
                boolean inCache = cache.containsKey(url);
                //Buffer to be used to write the data.
                byte output[] = new byte[BUFFER_SIZE];
                int index = inputStream.read(output, 0, BUFFER_SIZE);
                //Write the data from the buffer to the client, until there is no data left.
                while (index != -1) {
                    outputStream.write(output, 0, index);
                    //Write the data to the cache too if it isn't already there.
                    if (!inCache) {
                        byteArrayOutputStream.write(output, 0, index);
                    }
                    index = inputStream.read(output, 0, BUFFER_SIZE);
                }
                outputStream.flush();
                //Meaningful controlled output to console.
                if (url.substring(url.length() - 1).equals("/")) {
                    if (!inCache) {
                        System.out.println("Connected to: " + protocol + url);
                    } else {
                        System.out.println("Connected to: " + protocol + url + " from CACHE.");
                    }
                }
                //If the url isn't in the cache, add it.
                if (!inCache) {
                    //Check if cache has reached max capacity.
                    if (cache.size() > MAX_CACHE_SIZE) {
                        //reset index to 0 if it is at the end of the cache.
                        if (cacheIndex >= cache.size()) {
                            cacheIndex = 0;
                        }
                        //Get the next url to be removed (the oldest one), to make space for a new one.
                        String urlToRemove = (String) cache.keySet().toArray()[cacheIndex];
                        cache.remove(urlToRemove);
                        cacheIndex++;
                    }
                    //Write the bytes of data to the cache.
                    byte data[] = byteArrayOutputStream.toByteArray();
                    cache.put(url, data);
                }
            }
        } catch (Exception e) {
        }
    }
}