import {AvailableCredential, DIDMethods, mapFormat, isEudiFormat, ClaimDefinition, getAvailableFormatsForCredential, getDefaultFormatForCredential, isEudiCredential} from "@/types/credentials";
import EditCredentialModal from "../modal/EditCredentialModal";
import {PencilSquareIcon} from "@heroicons/react/24/outline";
import Dropdown from "@/components/walt/forms/Dropdown";
import ClaimsEditor from "@/components/walt/forms/ClaimsEditor";
import React from "react";

type Props = {
  credentialToEdit: AvailableCredential;
  credentialsToIssue: AvailableCredential[];
  setCredentialsToIssue: (credentials: AvailableCredential[]) => void;
};

// Extract credential subject from offer - handles both W3C and mDoc formats
function extractCredentialSubject(offer: any): any {
  // W3C format: offer.credentialSubject
  if (offer.credentialSubject) {
    return offer.credentialSubject;
  }
  // mDoc format: offer[namespace] where namespace is a key like 'eu.europa.ec.eudi.pid.1'
  const namespaceKeys = Object.keys(offer).filter(k =>
    typeof offer[k] === 'object' && !Array.isArray(offer[k])
  );
  if (namespaceKeys.length > 0) {
    // Return all namespace data combined for editing
    const combined: any = {};
    for (const ns of namespaceKeys) {
      combined[ns] = offer[ns];
    }
    return combined;
  }
  return offer;
}

export default function RowCredential({
  credentialToEdit,
  credentialsToIssue,
  setCredentialsToIssue,
}: Props) {
  const [credentialSubject, setCredentialSubject] = React.useState(
    extractCredentialSubject(credentialToEdit.offer)
  );
  // Get available formats for this credential (EUDI credentials have restricted formats)
  const availableFormats = getAvailableFormatsForCredential(credentialToEdit.id);
  const [selectedFormat, setSelectedFormat] = React.useState(
    getDefaultFormatForCredential(credentialToEdit.id)
  );
  const [selectedDID, setSelectedDID] = React.useState(DIDMethods[0]);
  const [modalVisible, setModalVisible] = React.useState(false);
  // Initialize claims from defaultClaims or empty array
  const [claims, setClaims] = React.useState<ClaimDefinition[]>(
    credentialToEdit.defaultClaims || []
  );

  React.useEffect(() => {
    setCredentialsToIssue(
      credentialsToIssue.map((credential) => {
        // Match by credential id (not offer.id which may not exist for EUDI credentials)
        if (credential.id === credentialToEdit.id) {
          let updatedCredential = { ...credential };

          // Update the offer with edited credential subject
          // For mDoc: credentialSubject is { namespace: { claims } }
          // For W3C: credentialSubject is { claims }
          const currentSubject = extractCredentialSubject(credential.offer);
          if (JSON.stringify(credentialSubject) !== JSON.stringify(currentSubject)) {
            // Check if this is mDoc format (namespaced) or W3C format
            if (credential.offer.credentialSubject) {
              // W3C format
              updatedCredential.offer = { ...credential.offer, credentialSubject };
            } else {
              // mDoc format - credentialSubject IS the namespaced data
              updatedCredential.offer = { ...credentialSubject };
            }
          }
          updatedCredential.selectedFormat = selectedFormat;
          updatedCredential.selectedDID = selectedDID;
          updatedCredential.editedClaims = claims;

          return updatedCredential;
        } else {
          let updatedCredential = { ...credential };
          updatedCredential.selectedFormat = selectedFormat;
          return updatedCredential;
        }
      })
    );
  }, [credentialSubject, selectedFormat, selectedDID, claims]);

  return (
    <>
      <div className="lg:flex flex-row gap-5 justify-between">
        <div className="flex flex-row gap-5 items-center">
          <div className="hidden sm:block bg-primary-400 w-[45px] h-[28px] rounded-lg"></div>
          <span className="text-gray-900 text-lg text-left">
            {credentialToEdit.title}
          </span>
          {(() => {
            try {
              return isEudiFormat(mapFormat(selectedFormat)) && (
                <span className="px-2 py-0.5 text-xs font-medium bg-blue-100 text-blue-800 rounded">
                  EUDI
                </span>
              );
            } catch {
              return null;
            }
          })()}
          <PencilSquareIcon
            onClick={() => {
              setModalVisible(true);
            }}
            className="h-4 text-gray-500 hover:text-primary-400 cursor-pointer"
          />
        </div>
        <div className="flex flex-row items-center gap-3 lg:w-5/12">
          <div className="hidden lg:block w-[2px] h-[2px] bg-gray-200"></div>
          <div className="w-full">
            <Dropdown
              values={availableFormats}
              selected={selectedFormat}
              setSelected={setSelectedFormat}
            />
          </div>
          {/* Hide DID dropdown for EUDI credentials - they use server keys */}
          {!isEudiCredential(credentialToEdit.id) && (
            <div className="w-full">
              <Dropdown
                values={DIDMethods}
                selected={selectedDID}
                setSelected={setSelectedDID}
              />
            </div>
          )}
        </div>
      </div>
      {/* Render ClaimsEditor only for EUDI formats */}
      {(() => {
        try {
          const format = mapFormat(selectedFormat);
          if (isEudiFormat(format)) {
            return (
              <ClaimsEditor
                credentialTitle={credentialToEdit.title}
                credentialId={credentialToEdit.id}
                claims={claims}
                onChange={setClaims}
              />
            );
          }
          return null;
        } catch {
          return null;
        }
      })()}
      <EditCredentialModal
        show={modalVisible}
        onClose={() => {
          setModalVisible(false);
        }}
        credentialSubject={credentialSubject}
        setCredentialSubject={setCredentialSubject}
        credentialTitle={credentialToEdit.title}
      />
    </>
  );
}
